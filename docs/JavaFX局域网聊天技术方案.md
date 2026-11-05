# JavaFX局域网聊天技术方案

> **Workspace:** /Users/lujc/workspace/trae/nexusChat

---

**User**

先 不要动代码 ，想做局域网点对点聊天，开发语言使用javaFX，探讨技术路线和实现

**Assistant**

好的，代码不动，咱们先系统聊聊技术路线。根据检索到的相关经验和局域网 P2P 聊天的通用方案，整理如下：

---

## 一、架构总览：选哪种 P2P 模式？

局域网点对点聊天在实际落地时，通常有两种架构选择：

| 模式 | 描述 | 适用场景 |
|------|------|---------|
| **角色可切换的对等架构** | 双方都同时开启 TCP 监听（做 ServerSocket），也都能主动发起连接；先连上的那条通道复用 | 最简单，双方启动后等发现/输入 IP 即可 |
| **完全去中心化** | 运行时自动协商谁是 Server 谁是 Client（如根据启动时间、IP 大小判定） | 对用户更友好，但实现稍复杂 |

**建议选型**：第一版先用 **角色可切换的对等架构** —— 双方启动时都自动监听一个固定端口（如 `15000`），谁先连上谁就是 Server 端，另一方是 Client。体验上对用户透明，但实现复杂度最低。

---

## 二、模块拆分（4 层）

```
┌─────────────────────────────┐
│   JavaFX UI 层 (FXML/CSS)    │   ← 聊天窗口、联系人列表、配置面板
├─────────────────────────────┤
│   业务协调层 (ChatService)   │   ← 消息路由、会话管理、状态同步
├─────────────────────────────┤
│   网络协议层 (NetworkStack)  │   ← TCP 连接管理、帧编解码、发现
├─────────────────────────────┤
│   本地存储层 (Storage)        │   ← 联系人列表、聊天记录（可选）
└─────────────────────────────┘
```

**关键设计原则**（来自之前 JavaFX + TCP 的经验）：
- **网络层绝不直接操作 UI 组件** —— 后台线程只负责收发数据，UI 更新必须切回 JavaFX 主线程（`Platform.runLater()`）
- **UI 展示文案和内部枚举解耦** —— 比如用 `enum Role { SERVER, CLIENT }`，而不是在代码里到处写中文字符串 `"服务端"` / `"客户端"`
- **监听端和已连接会话分开管理** —— `ServerSocket` 只负责 accept，每个 `SocketChannel` 独立一个会话对象

---

## 三、设备发现：怎么找到对方？

局域网 P2P 的核心问题之一是"怎么发现另一台设备"。三种方案：

### 方案 A：手动输入 IP（最简单，第一版必做）
用户在界面上填对方 IP 和端口，点连接。**零依赖、零复杂度**，缺点是不够"自动"。

### 方案 B：UDP 广播发现（推荐做增强功能）
```
启动 → 每 3 秒在 UDP 15001 端口广播自己的身份信息
     ↓
收到广播 → 解析对方 IP/昵称/端口 → 自动加入联系人列表
     ↓
用户在列表里点选 → 发起 TCP 连接
```

广播报文示例（JSON，方便调试）：
```json
{
  "type": "discover",
  "nickname": "Alice",
  "port": 15000,
  "timestamp": 1730790000000
}
```

### 方案 C：mDNS / DNS-SD（高级可选）
利用 JDK 自带的 `java.net.nsd`（JDK 9+）注册和发现服务。零配置，但跨平台兼容性需要实测。

**建议路线**：第一版先做 **A + B**（手动 IP + UDP 广播），mDNS 作为后续扩展。

---

## 四、通信协议：怎么发消息不粘包？

TCP 是字节流，没有消息边界，必须自己定义帧协议。这是之前多个经验反复踩坑的点。

### 推荐帧协议（简单高效的二进制帧）

```
┌──────┬──────┬──────────┬─────────────────┐
│ Magic│ Type │  Length   │    Payload      │
│ 2B   │ 2B   │ 4B (int)  │   N bytes       │
└──────┴──────┴──────────┴─────────────────┘
```

| 字段 | 说明 |
|------|------|
| Magic | 固定魔数，如 `0xCAFE`，用于快速校验 |
| Type | 消息类型枚举（TEXT / FILE / ACK / HEARTBEAT ...） |
| Length | Payload 长度（解决粘包核心） |
| Payload | 具体内容，文本消息可以用 JSON 编码 |

### 消息类型建议
```java
enum MessageType {
    HELLO,       // 握手
    TEXT,        // 文字消息
    FILE_META,   // 文件元数据
    FILE_CHUNK,  // 文件分片
    ACK,         // 确认
    HEARTBEAT,   // 心跳保活
    BYE          // 断开
}
```

### 粘包处理伪逻辑
```
读 socket → 先读 8 字节头 → 解析出 length
         → 循环读满 length 字节 payload
         → 交给业务层处理
         → 继续下一帧
```

---

## 五、线程模型

JavaFX 应用的线程约束比较严格，必须遵循：

```
┌───────────────────── JavaFX Application Thread ─────────────────┐
│                                                                   │
│  [UI 组件] ← Platform.runLater() ← [消息入队] ← 后台线程         │
│      ↑                                       │                    │
│  用户操作 → ChatService.send() → 网络层 → Socket OutputStream     │
│                                                                   │
└───────────────────────────────────────────────────────────────────┘

┌───────────────────── 后台线程池 ─────────────────────────────────┐
│  Thread-1: ServerSocket.accept()        // 监听连接              │
│  Thread-2: SocketInputStream 读循环      // 收消息（每连接一个） │
│  Thread-3: UDP 广播发送定时线程          // 设备发现              │
│  Thread-4: UDP 广播接收线程              // 设备发现              │
│  Thread-5: 心跳保活定时线程              // 维持连接              │
└───────────────────────────────────────────────────────────────────┘
```

**关键约束**（来自经验 1240301）：
- 后台线程拿到消息后，通过 `Platform.runLater()` 回切到主线程更新 UI
- 或者用 `BlockingQueue` 做线程间消息传递，UI 侧用 `AnimationTimer` 轮询（比 `runLater` 更容易控制频率）

---

## 六、JavaFX UI 结构建议

```
Main.fxml (根窗口)
├── TopBar.fxml         // 本机 IP 显示、端口配置、在线状态
├── ContactList.fxml    // 左侧：联系人列表（来自 UDP 发现）
│   └── ContactCell.fxml // 单个联系人卡片
├── ChatArea.fxml       // 右侧：聊天区域
│   ├── MessageList.fxml // 消息列表（ListView 或 VBox）
│   ├── InputBar.fxml    // 输入框 + 发送按钮
│   └── StatusBar.fxml   // 连接状态指示
└── Dialogs/
    ├── ConnectionDialog.fxml  // 手动输入 IP 连接
    └── SettingsDialog.fxml    // 昵称、端口等设置
```

**注意事项**（经验 1240301 踩过的坑）：
- FXML 中控件属性用 `disable` 不是 `disabled`
- 连接模式切换时，动态禁用/启用对应输入项（如 Server 模式禁用"目标 IP"）
- 不要在 FXML Controller 里写网络代码，Controller 只做 UI 事件转发

---

## 七、技术栈清单

| 分类 | 选型 | 理由 |
|------|------|------|
| **语言** | Java 17+ | LTS 版本，JavaFX 官方支持好 |
| **UI** | JavaFX (FXML + CSS) | 声明式界面，和控制器解耦 |
| **网络 I/O** | `java.net.Socket` + `BufferedReader/Writer` 起步 | 简单直接，后续可升级 NIO |
| **序列化** | 手搓二进制帧 + JSON for text | 文本消息用 JSON 易调试，控制帧用二进制高效 |
| **构建** | Maven / Gradle | JavaFX 插件完善 |
| **日志** | SLF4J + Logback | 网络调试必备 |
| **心跳保活** | 30s 间隔发 HEARTBEAT，90s 没收到就断链 | 局域网环境够用 |

---

## 八、分阶段实现路线

| 阶段 | 目标 | 核心产出 |
|------|------|---------|
| **V0.1 MVP** | 能连上、能发文字 | TCP Client/Server + 手动 IP + 帧协议 + 基本聊天窗口 |
| **V0.2 发现** | 自动找到对方 | UDP 广播发现 + 联系人列表 + 在线状态 |
| **V0.3 稳健** | 不断线、不乱码 | 心跳保活、重连机制、异常处理完善 |
| **V0.4 增强** | 更好用 | 聊天记录本地持久化、消息时间戳、多会话管理 |
| **V1.0 扩展** | 文件/图片传输 | FILE_META + FILE_CHUNK 帧、进度显示、断点续传（可选） |

---

## 九、风险点提前预警

| 风险 | 说明 | 应对 |
|------|------|------|
| **多网卡环境** | 一台机器可能有 WiFi + 有线 + 虚拟网卡，绑错 IP 就连不上 | 启动时枚举所有网卡，让用户选；或 UDP 广播自动选 |
| **NAT / 防火墙** | 同一局域网一般没问题，但 Windows 防火墙可能拦截 | 首次启动提示用户放行；局域网通信一般无需 NAT 穿透 |
| **粘包拆包** | TCP 字节流无边界 | 严格遵守帧协议的 Magic + Length 设计 |
| **线程安全** | JavaFX 要求 UI 操作在主线程 | 所有后台线程只入队，UI 线程消费队列 |

---

## 总结

推荐的 **技术路线**：

> **Java 17 + JavaFX (FXML/CSS) + 角色可切换对等架构 + 手动 IP + UDP 广播发现 + TCP 二进制帧协议 + Platform.runLater 线程切换**

第一版先啃 **MVP**（手动 IP + TCP 文字聊天），确保核心链路打通后再加自动发现和稳健性。你觉得这个路线 OK 吗？有没有想调整的地方，比如想直接上 NIO、或者想先跳过 UDP 广播？

---

**User**

整理前期架构的 md文件可以规范我们后续任务，
1，java25 ，javafx；
2，完全剥离服务端，纯粹点对点；
3，不要广播，手动添加ip，端口 纯粹点对点，隐私性保留
4，保留添加好友功能，聊天记录双方本地保留，可文字、图片、文件等数据传输
5，后续实现群聊。这是我大致方向

**Assistant**

用户要求整理一份架构设计的 MD 文件，并明确了几个方向性的调整：Java 25 + JavaFX、完全 P2P 无服务端、手动 IP 不广播、好友管理 + 本地聊天记录 + 多类型数据传输、后续群聊。

让我先看看当前工作目录的结构，然后创建这份架构文档。

目录是空的，需要从零创建项目结构和架构文档。用户明确了：
1. Java 25 + JavaFX
2. 完全 P2P，无服务端
3. 手动 IP/端口，不广播
4. 好友管理 + 本地聊天记录 + 多类型数据传输
5. 后续群聊

让我先创建一份完整的架构设计文档。

架构文档已经整理好，放在了 [docs/ARCHITECTURE.md](file:///Users/lujc/workspace/trae/nexusChat/docs/ARCHITECTURE.md)。

## 文档核心内容速览

### 📐 架构定位
- **完全无中心 P2P**：每个节点都是 Server + Client，启动时自动监听固定端口（默认 15000）
- **零广播、零自动发现**：好友只能手动 IP:Port 添加，隐私至上
- **Java 25 虚拟线程**：用阻塞式 I/O 即可高并发，无需 NIO Reactor 复杂度

### 🧩 4 层模块划分
```
JavaFX UI 层 → 业务协调层（ChatService/ContactManager/FileManager）→ 网络协议层（P2PNode/FrameCodec）→ 本地存储层（SQLite + 文件系统）
```

### 📡 通信协议
- **二进制帧**：`Magic(2B) + Type(2B) + Length(4B) + Payload(NB)` —— 彻底解决 TCP 粘包
- **Payload 统一 JSON**（UTF-8），易调试、易扩展
- **20+ 消息类型**覆盖握手、心跳、好友、文本、图片、文件（分块）、群聊（预留）

### 💾 本地存储
- SQLite 三表：`contacts` / `messages` / `file_transfers` + 群聊两表（预留）
- 文件存储：`~/.nexuschat/{images,files,temp}/` 分目录管理

### 📅 分 5 个 Phase 推进
| Phase | 内容 | 交付标准 |
|-------|------|---------|
| **1. MVP** | 骨架 + P2P 连接 + 帧编解码 + 好友添加 + 文字聊天 | 两台机器能发消息 |
| **2. 文件图片** | 分块传输 + 进度条 + 缩略图 | 能传 PDF/图片 |
| **3. 稳健性** | 自动重连 + 状态指示 + 历史分页 + 多网卡 | 断线能自愈 |
| **4. 群聊** | 群主协调转发 + 成员管理 | 多人聊天 |
| **5. 增强（可选）** | 搜索 + 引用 + E2E 加密 + 托盘通知 | 体验优化 |

### 🔑 几个关键设计决策
1. **会话 ID 生成**：双方 deviceId 排序后拼接，保证两端一致
2. **好友双向确认制**：A 加 B → B 接受 → 双方各自本地存 contacts
3. **文件分块 ACK 制**：每 64KB 一块，收到必须 ACK，超时重发
4. **JavaFX 线程安全**：后台虚拟线程只收包入队，UI 更新统一 `Platform.runLater`

---

有想调整的地方随时说，比如帧字段要不要加 CRC、SQLite 要不要换 H2、或者 Phase 顺序要变。确认无误后咱们再开项目骨架。

---

**User**

群聊的处理 我的想法是群聊 发起人 作为S，其他成员都是C，由S向c广播是否可行

**Assistant**

完全可行，而且这就是局域网 P2P 群聊最务实的方案。让我把这个设计展开说透，然后更新架构文档。

## 方案本质：群主 = 协调者 + 转发中枢

```
                    ┌───────────┐
                    │ 群主 S     │ ← 创建群的人，唯一协调者
                    │ (Node A)   │
                    └──┬──┬──┬──┘
                       │  │  │  各自一条 TCP 长连接
          ┌────────────┘  │  └────────────┐
          ▼               ▼               ▼
     ┌─────────┐    ┌─────────┐    ┌─────────┐
     │ 成员 C1 │    │ 成员 C2 │    │ 成员 C3 │
     │(Node B) │    │(Node C) │    │(Node D) │
     └─────────┘    └─────────┘    └─────────┘
```

**消息流向**：
```
C1 发群消息 → 发给群主 S → S 转发给 C1、C2、C3（全员）
C2 发群消息 → 发给群主 S → S 转发给 C1、C2、C3
群主自己发  → 直接在 S 本地产生 → S 转发给 C1、C2、C3
```

**这个方案的核心优势**：
- ✅ 只需要维护 **N-1 条连接**（群主到每个成员各一条），成员之间不直连
- ✅ 群主逻辑就是"收一条 → 遍历转发"，极其简单
- ✅ 和我们已有的单聊架构完全复用（成员到群主的 TCP 连接和单聊是同一个 `PeerConnection`）
- ✅ 成员节点无需改动任何代码就能支持群聊（只要能收/发 `GROUP_MESSAGE` 帧）

## 关键流程补充

### 加入群聊（两种方式）

```
方式一：群主邀请（GROUP_INVITE）
  群主 S → 发 GROUP_INVITE 给待加入者
       待加入者 → 收到弹窗 → 接受 → 成为群成员
       S 更新成员列表 → 广播 GROUP_JOIN 给所有现有成员

方式二：成员申请加入（后续扩展，需群主在）
  申请者 C → 连群主 S → 发 GROUP_JOIN_REQUEST
       群主 S → 弹窗确认 → 接受 → 广播 GROUP_JOIN
```

### 成员离群

```
C3 主动退出 → 发 GROUP_LEAVE 给 S
       S 更新成员列表 → 广播给所有人
       S 主动断开和 C3 的 TCP 连接

群主离线/退出 → 群暂时不可用
       所有成员 → 收到 BYE + GROUP_DISSOLVED（可选）
       后续扩展：群主离线后，成员中按某个规则（如 IP 最小）自动选举新群主
```

### 消息 ACK 和防丢

```
C1 发 GROUP_MESSAGE
    │
    ├─▶ S 收到 → 立即 ACK 给 C1（告诉 C1 "我收到了，会转发"）
    │
    └─▶ S 异步转发给 C2、C3（不用等 C2/C3 ACK 给 C1）
    
    这样 C1 的体验就是"发出去就立即显示已送达"，不会被慢成员阻塞
```

**注意**：ACK 只是"群主收到了"，不代表所有成员都收到了。局域网内丢包率极低，这样设计足够用。

## 需要更新架构文档的内容

让我把群聊章节补充完善：

好的，我需要找到群聊相关章节的位置，然后更新它。让我继续往后读找到群聊章节。

好，群聊章节在第576行附近，让我读一下那部分的内容，然后替换它。

好，现在我把群聊章节（第9章）完整替换成更详细的设计，包含用户说的"S/C"模型、加入流程、转发逻辑、群主离线处理、与单聊架构的复用关系等。

现在需要同步更新第 3.2 节的消息类型枚举，避免编号冲突：

对，有两处 GROUP_CREATE，旧的在 3.2 节（行161附近），新的在 9.8 节（行798附近）。需要把 3.2 节的旧群聊消息类型删掉，因为现在群聊消息类型在 9.8 节统一定义了，3.2 节只保留单聊相关的类型。让我读一下 3.2 节附近的内容。

好的，我需要把 3.2 节里旧的群聊消息类型（行160-165）替换成"详见 9.8 节"的引用，因为群聊消息类型在 9.8 节有完整定义了，编号也重新分配了（旧的 GROUP_CREATE=0x0040, GROUP_JOIN=0x0041 等 和新的编号不一致）。

架构文档群聊章节已完整更新。来看看你的方案落地后的全貌：

---

## ✅ 更新总结

群聊章节（§9）已从简略版升级为 **10 个小节的完整设计**，放在 [ARCHITECTURE.md](file:///Users/lujc/workspace/trae/nexusChat/docs/ARCHITECTURE.md) 里。核心改动：

### 🎯 你的方案确认：**群主 S 转发 + 成员 C**

```
         ┌───────────┐
         │ 群主 S     │ ← 发起人 = Server = 唯一转发中枢
         │ (Node A)   │
         └──┬──┬──┬──┘
            │  │  │   N-1 条 TCP 长连接（复用单聊的 PeerConnection）
       ┌────┘  │  └────┐
       ▼       ▼       ▼
    ┌─────┐ ┌─────┐ ┌─────┐
    │ C1  │ │ C2  │ │ C3  │  ← 成员之间零连接
    └─────┘ └─────┘ └─────┘
```

### 📋 新增内容一览

| 小节 | 覆盖的问题 |
|------|-----------|
| **9.2 Trade-off 分析** | 为什么群主转发是局域网最优方案，接受的边界条件（群主能看所有消息） |
| **9.3 群主职责清单** | 5 项职责 + `GroupLeaderState` 运行时状态对象 |
| **9.4 加入流程** | 群主邀请 + 成员申请两种方式的时序图 |
| **9.5 离群与离线** | 主动离群、心跳异常、群主离线三个场景的处理 |
| **9.6 ACK 机制** | 发送方只等群主 ACK，不被慢成员阻塞 |
| **9.7 代码复用** | 一个 `isGroupLeader()` 开关，单聊群聊完全复用 `PeerConnection` |
| **9.8 协议补全** | 15 个群聊专用消息类型（管理类 10 个 + 文件类 5 个） |
| **9.9 存储补充** | `groups.my_role` 字段 + `group_members.is_online` 运行时状态 |
| **9.10 Phase 界定** | 群聊 MVP 归 Phase 4，自动选举归 Phase 5 |

### 🔑 几个值得注意的点

1. **群主离线怎么办**：Phase 4 里群会暂时标记为"离线"，Phase 5 再做自动选举（按 deviceId 字典序选最小的当新群主）
2. **群主能看到所有群消息**：这是中心化转发的 trade-off，但在"朋友群/同事群"场景下基本可接受
3. **群文件传输**：走同样的群主中转，用 `GROUP_FILE_*` 帧，和单聊的文件分块逻辑复用
4. **成员零改动**：一个节点代码同时支持单聊 + 群聊，完全由消息类型路由

---

方案 OK 就可以开始搭项目骨架了。还有其他想调整的地方吗？

---

**User**

结合我们聊的 整理出 我们的设计目标，群聊 也直接加进去吧

**Assistant**

好，把群聊从"后续扩展"提升为核心设计目标。先快速确认一下现有架构文档的完整度，然后整理出一份聚焦的设计目标文档。

现在创建设计目标文档，然后同步更新架构文档里的 Phase 规划：

现在同步更新架构文档里的 Phase 里程碑，让两份文档一致：

我需要重新读 Phase 里程碑部分，然后更新它。

现在更新 ARCHITECTURE.md 里的 Phase 里程碑，让它和 DESIGN_GOALS.md 一致，同时把 §1 核心设计原则里的群聊定位也调整一下。

最后更新架构文档版本号和开头：

也更新一下架构文档 §1 里的"渐进增强"原则，因为群聊已经不是后续了，而是设计目标的一部分。

搞定。现在两份文档形成了完整的"决策 + 实现"双轨：

---

## 📚 产出总结

### 新建 [DESIGN_GOALS.md](file:///Users/lujc/workspace/trae/nexusChat/docs/DESIGN_GOALS.md)
设计目标文档，聚焦 **"做什么 + 边界在哪 + 优先级"**：

| 章节 | 内容 |
|------|------|
| **一句话定位** | 局域网内，完全无中心，纯点对点的聊天工具 |
| **核心主张** | 零服务端、零广播、数据本地、隐私默认 |
| **技术选型** | Java 25 + JavaFX 25 + Gradle + SQLite + 虚拟线程 |
| **功能清单** | 30 项功能分 P0/P1/P2/P3 四档优先级 |
| **群聊架构** | 群主 S = Server，成员 C = Client，消息经群主转发 |
| **非目标** | 明确排除自动发现、NAT 穿透、多设备同步、E2E 加密 |
| **边界条件** | 多网卡、防火墙、群主离线、传输中断等场景的预期行为 |
| **里程碑** | Phase 1-5，群聊已从"后续"提升至 Phase 3 核心交付 |
| **开放问题** | 3 个待你确认的决策点 |

### 更新 [ARCHITECTURE.md](file:///Users/lujc/workspace/trae/nexusChat/docs/ARCHITECTURE.md)
详细实现蓝图，版本升至 v1.0：

- §1 开头关联设计目标文档
- §9 群聊章节扩展为 10 个完整小节（群主职责、加入流程、离群处理、ACK 机制、代码复用、协议枚举、存储补充、Phase 界定）
- §12 Phase 里程碑重排：群聊从 Phase 4 提前到 **Phase 3**，稳健性降到 Phase 4

---

## 🗂 两份文档的分工

```
docs/
├── DESIGN_GOALS.md    ← "我们要做什么"（目标、边界、优先级）
└── ARCHITECTURE.md     ← "具体怎么做"（协议、Schema、类职责、伪代码）
```

以后遇到分歧，先查 DESIGN_GOALS.md 看"这事儿要不要做"，再查 ARCHITECTURE.md 看"这事儿怎么实现"。

---

## ❓ 3 个待你拍板的问题

| # | 问题 | 我的建议 |
|---|------|---------|
| 1 | Java 25 正式版 2025 年 9 月发布，现在用 Java 24 EA 先行开发？ | 建议先用 24 EA 或 23，等 25 GA 了无缝升级 |
| 2 | 局域网内明文通信 OK 吗？还是现在就加 TLS？ | 局域网明文够用，TLS 加到 P3 |
| 3 | 群主离线后要不要做自动选举新群主？ | 先不做，P3 再加，Phase 3 群聊 MVP 只做"离线标记" |

确认这三点就可以开项目骨架了。

---

**User**

如下调整：
1，构建采用maven的方式；
2，聊天时的监听端口可以再程序启动后，监听端口是否可以让用户修改，如果可修改 就有修改功能，如果不可修改，就要明显提示出监听端口是啥

**Assistant**

好，两个调整都明确。让我先确认一下关于"运行时改端口"的技术细节，再更新文档。

**关于运行时修改监听端口的技术边界**：TCP ServerSocket 一旦 `bind` 到某个端口就无法在运行时切换——必须先关闭旧 ServerSocket，再在新端口上重新 `bind`。已建立的长连接不会受影响（因为对端连的是旧端口，但 Socket 是已建立的独立连接），只是**新入站连接**需要走新端口。这个边界需要在文档里明确说明。

现在同步更新两份文档：

好，我现在开始改两份文档。需要改的点：

1. DESIGN_GOALS.md:
   - 构建工具 Gradle → Maven
   - P0 功能 #1 端口描述要改，加上"界面上明显展示当前监听端口"和"设置面板可修改端口"
   - 加一个新功能项"运行时修改监听端口"
   
2. ARCHITECTURE.md:
   - 构建工具 Gradle → Maven
   - AppConfig 里端口项的描述要细化
   - P2PNode 要加 `changeListenPort(int newPort)` 方法
   - 说明修改端口时的技术行为：关闭旧 ServerSocket → 重新 bind → 已有连接不受影响

让我一次性改完。

继续改 DESIGN_GOALS.md 的 P0 功能清单，把端口相关的功能补充完整。

现在更新架构文档：

现在在架构文档的网络层设计里补充端口修改的 API，以及配置项的细化：

好的，现在我要在 P2PNode 的 API 里加上 changeListenPort 方法，然后在下面补充端口修改的技术细节说明。

好，现在我要在 P2PNode 里加上 `changeListenPort` 方法，然后在 PeerConnection 后面补充一段关于端口修改的完整技术设计。

现在更新配置项章节，细化端口配置的说明：

好的，让我读取配置项那一节，然后修改端口的描述。

好，现在我要更新配置项表格，把端口那一行的描述改详细，同时在安全章节后面加一段关于端口修改的 UI 展示要求。

最后快速校验两份文档的关键改动都到位了：

还有一处残留的 Gradle 引用，修掉：

全部改完。两份文档已同步更新：

---

## ✅ 本次改动总结

### 1. 构建工具：Gradle → Maven

| 文档 | 改动 |
|------|------|
| [DESIGN_GOALS.md](file:///Users/lujc/workspace/trae/nexusChat/docs/DESIGN_GOALS.md) §技术选型 | Gradle (Kotlin DSL) → **Maven** |
| [ARCHITECTURE.md](file:///Users/lujc/workspace/trae/nexusChat/docs/ARCHITECTURE.md) §技术栈 | 同上 |
| [ARCHITECTURE.md](file:///Users/lujc/workspace/trae/nexusChat/docs/ARCHITECTURE.md) §Phase 1 | 骨架搭建写 Maven |

### 2. 监听端口：可见 + 可改

这个拆成了三件事，每件都写进了文档：

| 文档 | 位置 | 内容 |
|------|------|------|
| DESIGN_GOALS.md §P0 功能 | 新增 1a + 1b | `1a 监听端口可见` + `1b 运行时修改监听端口` |
| ARCHITECTURE.md §P2PNode API | 新增两个方法 | `changeListenPort(int)` + `getListenPort()` |
| ARCHITECTURE.md §P2PNode 内部逻辑 | 新增伪代码 | 关闭旧 ServerSocket → 绑定新端口 → 触发 UI 事件 → 写配置 |
| ARCHITECTURE.md §技术边界 | 新增 | 已有连接不受影响、端口冲突兜底、对端重连需手动更新 |
| ARCHITECTURE.md §10.2（新增） | 专门一节 | UI 展示要求表：状态栏、设置面板、添加好友对话框、成功/失败提示 |

### 3. 修改端口的技术行为（你需要知道的边界）

```
用户点"应用新端口 15001"
       │
       ▼
  关闭旧 ServerSocket (15000)
       │  ← 已有的 3 条 PeerConnection 不受影响，继续正常通信
       ▼
  在 15001 上 bind 新 ServerSocket
       │  ← 新入站连接必须走 15001
       ▼
  写回 config.properties
       │
       ▼
  UI 状态栏刷新 → "🔌 监听端口: 15001"
       │
       ▼
  如果端口被占用 → catch IOException → Alert 弹窗"端口 15001 已被占用"，旧 ServerSocket 保持不变
```

唯一的 trade-off：对端在握手时拿到的是旧端口号，如果他们之后掉线重连，需要手动更新你的新端口。局域网内这个影响很小——改端口一般是启动时改好，不会运行中频繁改。

---

还有其他调整吗？没问题的话下一步就可以开 Phase 1 的项目骨架了（Maven + JavaFX + Java 25）。

---

**User**

大哥按照你的理解 现在是什么时间？

**Assistant**

根据系统环境显示的信息，今天的日期是 **2026 年 11 月 5 日**。

现在应该是白天，具体时间我拿不到实时钟——我只能看到系统给的日期。

你问这个是在担心什么？是不是之前提到的 Java 25 发布时间（预计 2025 年 9 月）的问题？如果已经是 2026 年 11 月了，那 Java 25 早就 GA 了，直接用就行。

---

**User**

那就确认 
1，java25；
2，暂定铭文传输；
3，群主离线后，随机一个就行，不用投票，直到就剩2人是变单聊。

**Assistant**

好，三个点全部确认，同步更新两份文档。

好，先改 ARCHITECTURE.md 的 §9.5 群主离线处理（核心改动）：

现在更新 §9.10 里的 Phase 界定，群主自动选举/降级从 P3 提到 P3（核心）：

更新 §11 安全与隐私里的通信保密（明文已定案）：

更新 Phase 里程碑里的群聊内容和 Phase 5：

ARCHITECTURE.md 搞定。现在同步 DESIGN_GOALS.md：

最后删掉 DESIGN_GOALS.md 底部的开放问题（三个都已解决）：

全部同步完毕。来快速总览一下这次三个决策带来的变化：

---

## ✅ 三个决策已落档

### 1. Java 25 ✅ 已确认
- 两份文档不再有"先开发后升级"的模糊表述
- §风险章节删掉了"Java 25 发布时间"这条风险
- Maven + JavaFX + Java 25 作为既定组合

### 2. 明文传输 ✅ 已确认
- ARCHITECTURE.md §安全与隐私：通信保密标记为"已确认：局域网内明文传输"
- DESIGN_GOALS.md §非目标：端到端加密标记为"已定案排除"
- Phase 5 里也删了 TLS 相关条目

### 3. 群主离线 → 一致随机选新群主 + 2人转单聊 ✅ 已确认

这是本次最大的改动，写进了 **Phase 3 核心功能**（不是后续增强）：

```
群主离线，心跳超时
       │
       ▼
  剩余在线成员每人独立算一遍：
  
  sort(all deviceIds) + 离线时间戳(分钟粒度) → hash → random seed
       │
       ▼
  Collections.shuffle(members, seed)
       │
       ▼
  所有人得到相同的 shuffle 结果 → 取第一个就是新群主
       │
       ▼
  分支：
  ├── 剩 1 人 → 群解散，删除本地群记录
  ├── 剩 2 人 → 自动降级单聊，双方直接点对点
  └── 剩 3+ 人 → 被选中的自动承担群主职责
```

**为什么要用"一致 hash + shuffle"而不是简单 random？** 因为不能让每个节点真的独立 random——那样 A 选了 B、B 选了 C、C 选了 A，全乱套了。用相同的 seed 大家独立算，结果保证一样。

### 4. 构建工具 Maven + 监听端口可修改 ✅ 上次已改

### 📋 已确认决策表（新增章节）

DESIGN_GOALS.md 底部现在有一张表，把所有拍板的事记下来，以后不会翻来覆去：

| # | 决策项 | 结论 |
|---|--------|------|
| 1 | Java 版本 | **Java 25** |
| 2 | 通信加密 | **明文传输** |
| 3 | 群主离线处理 | **一致随机选新群主**；剩 2 人转单聊；剩 1 人解散 |
| 4 | 构建工具 | **Maven** |
| 5 | 监听端口 | 可配置 + 运行时可改 + UI 明显展示 |

---

文档全齐了，下一步就可以开 Phase 1 骨架了。继续？

---

**User**

1，群聊变降级是否不太合理？群聊本身还是要保留的？比如标记离线状态是否更好？

**Assistant**

你说得对，"自动降级为单聊"确实不合理——**群聊的上下文（群名、群历史、群成员结构）不应该因为群主离线就割裂**。让我重新梳理一下，然后改文档。

## 问题出在哪

之前的设计有个思维误区：觉得"2 人选一个当群主转发"太浪费，不如直接变单聊。但实际上：

| 问题 | 说明 |
|------|------|
| **群历史割裂** | 之前群里聊的内容在群会话里，降级后新消息跑到单聊会话里，用户翻历史断掉了 |
| **群信息丢失** | 群名、成员列表（包括离线的）、群头像这些都白建了 |
| **体验突兀** | 正在群里聊得好好的，群主电脑关了，界面突然跳去单聊窗口 |
| **原群主回来怎么办** | 群主回来后，之前降级期间的"单聊消息"怎么并回群里？无解 |

## 改进后的方案：群聊始终是群聊

**统一规则，不管剩几人（只要 ≥ 2），都保持群聊结构**：

```
群主离线
       │
       ▼
所有剩余在线成员独立算：
  sort(在线 memberIds) + 离线时间戳(分钟粒度) → hash → shuffle → 取第一个
       │
       ├── 剩 1 人 → 群解散（确实没人了），本地删除群记录
       │
       └── 剩 ≥ 2 人 → **一致随机选新群主，保持群聊结构**
             │
             ▼
             被选中的自动承担群主职责（转发、维护成员列表）
             UI 标记："原群主已离线，XXX 自动接任群主"
             群聊历史、群名、群成员结构 **全部保留**
```

**关键变化**：2 人场景下不再"降级单聊"，而是照常选新群主走群聊模式。虽然 2 人走"群主转发"有点形式化（群主自己发消息相当于本地 loopback），但换来的是：
- ✅ 群历史完整不断裂
- ✅ 群主回来后能无缝恢复
- ✅ 规则统一，所有 ≥ 2 人的情况只走一个逻辑分支
- ✅ UI 上群还是那个群，不会跳来跳去

现在同步改两份文档：

好，先改 ARCHITECTURE.md §9.5 群主离线核心逻辑：

更新 §9.10 Phase 界定：

更新 Phase 3 里程碑：

ARCHITECTURE.md 搞定。现在同步 DESIGN_GOALS.md：

全部改完，做最后一次确认扫描：

好，剩下 4 处全是解释"为什么不降级"的说明文字，没有残留的旧逻辑了。

---

## ✅ 修正总结

**核心改动**：**群聊永远是群聊，不降级单聊**。

| 之前（有问题） | 现在（合理） |
|------|------|
| 群主离线 + 剩 2 人 → 自动降级单聊 | 群主离线 + 剩 2 人 → 照常一致随机选新群主，**保持群聊模式** |
| 降级后群历史割裂 | 群历史、群名、群成员结构**全程保留** |
| 群主回来后无法恢复 | 群主回来作为普通成员自动加入新群主的群 |

### 统一规则（不管剩几人）

```
群主离线
       │
       ▼
  剩 1 人 → 群解散（没人了）
  剩 ≥ 2 人 → 一致随机选新群主，保持群聊结构
              （不管是 2 人还是 10 人，规则统一）
```

### 为什么 2 人也走群聊模式不浪费？

虽然 2 人走"群主转发"形式上有点绕（群主自己发消息相当于本地 loopback），但：
- ✅ **代码路径统一** —— 不需要额外写一个"降级分支"，群聊逻辑一套通吃
- ✅ **群历史不割裂** —— 群主回来能看到完整的群聊上下文
- ✅ **UI 不跳窗** —— 用户不会突然被丢到单聊界面

两份文档（[DESIGN_GOALS.md](file:///Users/lujc/workspace/trae/nexusChat/docs/DESIGN_GOALS.md) + [ARCHITECTURE.md](file:///Users/lujc/workspace/trae/nexusChat/docs/ARCHITECTURE.md)）的 11 处相关表述已全部同步更新，包括已确认决策表、Phase 3 交付标准、§9.5 详细流程、§9.10 Phase 界定。

还有需要调整的吗？没问题可以开始 Phase 1 骨架了。

---

**User**

1，群聊永不降级；李现后可以再上线后重连，已经收到的聊天记录保留

**Assistant**

明白，"李现"应该是手滑打错，是"离线后可以再上线重连"对吧？这两点其实文档里大部分已经覆盖了，但让我确认并补齐可能遗漏的细节：

需要补充的场景：**不仅是群主离线重连，任何成员离线后都能重新上线**，并且已收到的聊天记录因为存在本地 SQLite 里天然不会丢。让我把这些边界写清楚：

现在在 DESIGN_GOALS.md 的边界条件表里同步补一条：

最后同步更新已确认决策表：

全部同步完毕。

---

## ✅ 两条确认已落档

### 1. 群聊永不降级
这一点之前已经改好，本轮再次强化——边界条件表里新增一行明确写"**群聊就是群聊，结构永不降级**"。

### 2. 离线后可以上线重连 + 已收到记录保留

核心原理很简单：**聊天记录存在你自己机器的 SQLite 里**，跟谁是群主、你在不在线没有半毛钱关系。只要你的应用没被卸载：

```
任何成员（群主 / 旧群主 / 普通成员）
       │
       ▼
  离线了（关机 / 断网）
       │
       ▼
  再上线启动应用
       │
       ▼
  自动扫描本地已知的群主 IP:Port → 连接
       │
       ▼
  恢复在线状态，继续收发消息
       │
       ├── 本地已收到的历史 → ✅ 全部在（存在 SQLite 里）
       ├── 群名、群成员列表 → ✅ 全部在（存在 SQLite 里）
       └── 离线期间别人发的消息 → ❌ 不补发（P2P 无服务器存储）
```

两份文档的 §边界条件 和 §已确认决策表 各新增了一条描述，Phase 3 里程碑也补了"原群主上线后作为普通成员重新加入"。

---

还有需要调整的吗？没问题的话，**文档阶段全部收口**，可以开始搭 Phase 1 Maven 骨架了。

---

**User**

按照现在的情况，设计出数据库设计，给出数据库表、关系图

**Assistant**

好，让我先查看现有架构文档里的数据库部分，然后根据我们所有确认的决策设计完整的 Schema。

好，现在基于所有已定方案设计完整 Schema。我会把它写成独立文档，包含 E-R 关系图，然后同步更新 ARCHITECTURE.md 里的旧版数据库章节。

好，现在更新 ARCHITECTURE.md 里的旧版数据库章节，让它指向新文档：

现在清理 ARCHITECTURE.md 里 §9.9 残留的旧群聊表定义：

全部搞定。现在你的项目文档体系有了明确的分工，数据库部分独立成章。

---

## 📊 数据库设计产出

### 新建 [DATABASE.md](file:///Users/lujc/workspace/trae/nexusChat/docs/DATABASE.md)

完整的数据库设计文档，8 张表 + E-R 图 + 业务查询 + 状态流转。

### 🎯 本次设计的核心决策（相比之前的简单版）

| 决策 | 说明 |
|------|------|
| **新增 `conversations` 会话表** | 之前只是 `messages` 里放 `conversation_id` 字符串，现在把会话提升为实体。单聊和群聊**统一一张表**（type 字段区分），UI 侧查询直接查一张表不用 UNION |
| **新增 `group_leader_ledger` 群主变更历史表** | 每次一致随机选举都记一条，reason=AUTO_ELECTION。这是群主自动轮换的审计日志，出问题时可以追溯"谁在什么时候因为什么接的手" |
| **快照优于关联** | `messages.sender_nickname`、`group_members.nickname` 都存快照值，**不做外键关联**。防止对方改昵称后历史消息显示错乱 |
| **运行时状态与持久化分离** | `contacts.is_online`、`group_members.is_online` 由心跳实时更新，重启后重置为离线。不靠持久化驱动 |
| **SQLite WAL + 多线程安全配置** | 建表时就设置好 journal_mode=WAL、busy_timeout=30s，避免并发写入锁死 |

### 📈 完整 E-R 图（概览）

```
app_config (键值对)
    └── 配置端口/昵称/主题等

contacts (好友)
    ├── 1:N → conversations (单聊)
    ├── 1:N → messages
    └── 1:N → file_transfers

conversations (统一会话)
    ├── type='PRIVATE' → contacts
    └── type='GROUP'   → groups
    └── 1:N → messages

groups (群)
    ├── 1:N → group_members
    └── 1:N → group_leader_ledger (群主变更日志)
```

### 📋 8 张表一览

| # | 表名 | 用途 | 核心字段 |
|---|------|------|---------|
| 1 | `app_config` | 应用配置键值对 | key / value |
| 2 | `contacts` | 好友 | device_id (UK) / nickname / ip / port |
| 3 | `conversations` | **统一单聊+群聊会话** | type (PRIVATE/GROUP) / contact_id / group_id |
| 4 | `messages` | 聊天记录 | conversation_id / sender_device_id / msg_type / direction |
| 5 | `file_transfers` | 文件传输记录 | transfer_id / conversation_id / status / progress |
| 6 | `groups` | 群信息 | my_role / current_leader_id / status |
| 7 | `group_members` | 群成员 | (group_id, device_id) PK / is_leader / is_online |
| 8 | `group_leader_ledger` | **群主变更历史** | old_leader_id → new_leader_id / reason / timestamp |

### 🔗 文档链接更新

ARCHITECTURE.md 已更新：
- §5 本地存储 → 链接 DATABASE.md，旧 SQL 内联定义全部移除
- §9.9 群聊存储 → 链接 DATABASE.md §3.6-3.8

现在三份文档分工清晰：

```
docs/
├── DESIGN_GOALS.md     ← "做什么 + 边界在哪"（决策基准）
├── ARCHITECTURE.md     ← "系统怎么设计"（架构蓝图）
└── DATABASE.md         ← "数据怎么存"（Schema + E-R + SQL）
```

---

Phase 1 骨架需要建数据库吗？还是先确认文档阶段没有其他调整了？

---

**User**

增加好友分组功能，只是对已经添加的好友进行分组管理，无聊天相关功能

**Assistant**

好，好友分组功能明确：**纯本地标签管理，和聊天/群聊无关**。这就简单了——一张分组表 + 一张关联表，多对多关系。

先更新 DATABASE.md 的 E-R 图和表定义：

E-R 图太复杂不好替换，直接在 §3.8 之后追加两张新表定义：

好，DATABASE.md 搞定。现在同步更新 DESIGN_GOALS.md 的功能清单和决策表：

现在更新里程碑，把好友分组放进 Phase 1 骨架里（因为很简单，两张表搞定）：

最后同步更新 ARCHITECTURE.md Phase 1 里程碑：

全部搞定。

---

## ✅ 好友分组功能已纳入

### 数据库层（DATABASE.md §3.9-3.10）

新增两张表，**和群聊的 `groups` 完全独立**（好友标签 ≠ 群聊）：

```
contact_groups (好友分组标签)
    ├── id: UUID
    ├── name: "同事" / "项目A"
    ├── color: UI 显示色 "#4A90D9"
    └── sort_order: UI 排序

contact_group_membership (多对多关联)
    ├── (group_id, contact_id) PK
    ├── FK → contact_groups (ON DELETE CASCADE)
    └── FK → contacts (ON DELETE CASCADE)
```

### 业务规则

| 规则 | 说明 |
|------|------|
| **多对多** | 一个好友可在多个分组（"张三"同时在"同事"+"项目A"），一个分组可含多个好友 |
| **纯本地** | 分组信息不同步给对端，完全是我自己的标签 |
| **无默认分组** | 首次启动不建"全部好友"，所有好友默认显示在"未分组"区域 |
| **删除级联** | 删好友自动从所有分组移除；删分组自动清空该分组的成员关系 |
| **独立概念** | `contact_groups` ≠ `groups`（群聊），零外键关联，零业务耦合 |

### UI 侧预想（后续 Phase 1 实现）

```
通讯录主视图：
├── 📁 同事 (12)
│   ├── 张三  ●
│   └── 李四  ○
├── 📁 项目A (5)
│   ├── 张三  ●
│   └── 王五  ●
├── 📁 大学同学 (20)
├── 📁 未分组 (3)    ← 没有任何标签的好友
│   └── 赵六  ○
└── ➕ 新建分组

好友右键菜单：
  ├── 移动到分组 → 同事 / 项目A / + 新建
  ├── 添加到多个分组...
  └── 从所有分组移除
```

### 更新的文档

| 文档 | 改动 |
|------|------|
| [DATABASE.md](file:///Users/lujc/workspace/trae/nexusChat/docs/DATABASE.md) | 新增 §3.9 contact_groups + §3.10 contact_group_membership，含 4 条业务查询 SQL |
| [DESIGN_GOALS.md](file:///Users/lujc/workspace/trae/nexusChat/docs/DESIGN_GOALS.md) | P0 新增 9a 好友分组功能，Phase 1 里程碑更新 |
| [ARCHITECTURE.md](file:///Users/lujc/workspace/trae/nexusChat/docs/ARCHITECTURE.md) | Phase 1 SQLite 初始化改为 9+2=10 张表 |

### 现在的完整表清单（10 张）

| # | 表 | 用途 | 所属 |
|---|------|------|------|
| 1 | app_config | 应用配置 | 通用 |
| 2 | contacts | 好友 | 单聊 |
| 3 | **contact_groups** | **好友分组标签** | **新增** |
| 4 | **contact_group_membership** | **好友-分组关联** | **新增** |
| 5 | conversations | 统一会话（单聊+群聊） | 通用 |
| 6 | messages | 聊天记录 | 通用 |
| 7 | file_transfers | 文件传输记录 | 通用 |
| 8 | groups | 群聊 | 群聊 |
| 9 | group_members | 群成员 | 群聊 |
| 10 | group_leader_ledger | 群主变更历史 | 群聊 |

---

文档应该都齐了。继续加功能还是开始 Phase 1 骨架？

---

**User**

`/Users/lujc/workspace/trae/nexusChat/docs/ARCHITECTURE.md`群聊 不要解散的动作 可以自己手动删除群聊

**Assistant**

好，群聊永不自动解散。只剩 1 人时群保持存在（标记 INACTIVE），用户可以手动删除。让我找出所有涉及"自动解散"的地方：

好，涉及面明确了。逐个修：

**先修 ARCHITECTURE.md §9.5 群主离线核心逻辑：**

更新群主离线的场景表：

更新 §9.10 Phase 界定：

更新 Phase 3 里程碑：

ARCHITECTURE.md 搞定。现在修 DATABASE.md：

更新 DATABASE.md 里剩余的 DISBANDED 引用和状态流转图：

最后修 DESIGN_GOALS.md：

最后扫一遍确认没有遗漏：

剩下的全是**确认排除自动解散**的描述文字，没有残留旧逻辑了。干净。

---

## ✅ "群聊永不自动解散" 已落档

### 改动核心

| 之前 | 现在 |
|------|------|
| 群主离线 + 剩 1 人 → **群解散** | 群主离线 + 剩 1 人 → **自己当群主，群 INACTIVE** |
| DISBANDED 状态 | 改名为 **DELETED**，只由用户手动触发 |
| disbanded_at 字段 | 改名为 **deleted_at** |

### 现在的群主离线统一规则（一个分支通吃所有情况）

```
群主离线，心跳超时
       │
       ▼
  剩 ≥ 1 人 → 一致随机选新群主
    ├── 剩 1 人 → 自己当群主，群 INACTIVE（等别人上线自动恢复）
    ├── 剩 2 人 → 一致随机选一个，群 ACTIVE
    └── 剩 3+ 人 → 一致随机选一个，群 ACTIVE
```

### groups.status 三个状态

| 值 | 含义 | 触发 |
|----|------|------|
| **ACTIVE** | 群正常运转 | 正常创建 / INACTIVE 后有人上线自动恢复 |
| **INACTIVE** | 暂时只有自己在线（自己是群主） | 群主离线 + 只剩自己 |
| **DELETED** | 用户手动删除 | 唯一触发方式 |

### 三份文档改动统计

| 文档 | 改动数 | 关键位置 |
|------|--------|---------|
| ARCHITECTURE.md | ~7 处 | §9.5 流程 / §9.10 阶段表 / Phase 3 里程碑 |
| DATABASE.md | ~5 处 | groups.status 枚举值 / 状态流转图 / 业务查询 |
| DESIGN_GOALS.md | ~5 处 | P3 功能 / 群聊架构表 / 边界条件 / 决策表 |

### 已定案排除的群聊行为（三不）

| ❌ 排除项 | 替代方案 |
|----------|---------|
| **自动降级单聊** | 永远保持群聊结构 |
| **自动解散** | 永不自动消失，只有用户手动删 |
| **群主身份自动归还** | Phase 5 做手动转让 |

---

现在决策表里"群主离线处理"这条的结论是：

> **一致随机选新群主**；群聊结构**永不降级单聊**；**永不自动解散**，只剩 1 人自己当群主（INACTIVE）；用户手动删除才算消失

文档体系应该彻底稳定了。继续加功能还是开 Phase 1 骨架？

---

**User**

SqlLite 是否支持 myBatisPlus 

**Assistant**

好问题。**SQLite 官方支持 MyBatis-Plus，但不是 100% 无痛**。结合经验和 NexusChat 的场景，分三档说：

## 结论：能用，但要注意几个坑

| 维度 | 状态 |
|------|------|
| MyBatis-Plus 核心 CRUD（selectById / insert / updateById / deleteById） | ✅ 直接可用，没问题 |
| 分页插件（PaginationInnerInterceptor） | ✅ 需要配置 SQLite 的 PaginationDialect，加了就能用 |
| 自动填充（MetaObjectHandler 填充 create_time / update_time） | ✅ 可用，但 SQLite 不支持 `ON UPDATE CURRENT_TIMESTAMP`，得用触发器模拟 |
| @TableLogic 逻辑删除 | ✅ 可用 |
| @Version 乐观锁 | ✅ 可用 |
| 复杂 SQL（XML 写的自定义 SQL） | ⚠️ 注意 SQLite 语法不兼容（见下面） |
| IdType.AUTO 自增主键 | ❌ SQLite 的 AUTOINCREMENT 有坑，建议用 IdType.INPUT（自己生成 UUID） |

## NexusChat 要不要上 MyBatis-Plus？

坦率说：**NexusChat 不太需要 MyBatis-Plus**，原因：

| 因素 | 分析 |
|------|------|
| **我们的 SQL 都很简单** | 9 张表，CRUD 为主，没有复杂 join、没有多条件动态拼装 |
| **MyBatis-Plus 最大的价值在复杂项目** | 它的优势（自动生成 XML、动态 SQL 拼接、多租户、自动填充）在小项目里是负担 |
| **SQLite + MyBatis-Plus 有适配成本** | 需要配置方言、分页插件、自动填充触发器，还得避开 IdType.AUTO 等不兼容点 |
| **NexusChat 是 JavaFX 桌面应用** | 不是 Web 项目，MyBatis-Plus 的生态（spring-boot-starter、内置连接池等）反而增加体积 |

## 推荐方案：纯 JDBC + 自己写个轻量 Repository

```java
// 一个典型的 Repository，代码量其实和 MyBatis-Plus 差不多
public class ContactRepository {

    private final Connection conn;  // 从 SQLite JDBC 获取

    public Optional<Contact> findById(String id) throws SQLException {
        String sql = "SELECT * FROM contacts WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    public List<Contact> findAll() throws SQLException {
        List<Contact> list = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM contacts ORDER BY nickname")) {
            while (rs.next()) list.add(mapRow(rs));
        }
        return list;
    }

    public void insert(Contact c) throws SQLException {
        String sql = "INSERT INTO contacts (id, device_id, nickname, added_at) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, c.getId());
            ps.setString(2, c.getDeviceId());
            ps.setString(3, c.getNickname());
            ps.setLong(4, c.getAddedAt());
            ps.executeUpdate();
        }
    }
}
```

**对比 MyBatis-Plus：**

| 对比项 | 纯 JDBC | MyBatis-Plus |
|--------|---------|-------------|
| 代码量 | 每个 Repository 约 80-120 行 | 继承 BaseMapper 几乎零代码 |
| SQLite 适配 | 零成本 | 需要配置方言、触发器 |
| 依赖引入 | 只加 `org.xerial:sqlite-jdbc` | 加 `mybatis-plus-boot-starter` + Spring Boot（我们不用 Web，太重） |
| 学习成本 | 会 SQL 就行 | 要理解 @TableName、@TableId、PaginationInnerInterceptor、自动填充 |
| 调试 | 直接看 SQL 字符串 | 要开 SQL 日志，出错了看 XML 映射 |
| 复杂度上限 | 约 20 个 Repository 后开始繁琐 | 复杂项目反而简单 |

**NexusChat 的 9 张表，手写 Repository 总计约 800-1000 行**，不复杂。

## 如果你还是想用 MyBatis-Plus

配置要点（经验 2141768 验证过的）：

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.xerial</groupId>
    <artifactId>sqlite-jdbc</artifactId>
    <version>3.47.0.0</version>
</dependency>
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-core</artifactId>  <!-- 只用 core，不带 spring-boot-starter -->
    <version>3.5.7</version>
</dependency>
```

```java
// MyBatis-Plus 配置
MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
// 必须加 SQLite 分页方言
interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.SQLITE));
```

```sql
-- AUTOINCREMENT 必须用触发器模拟 ON UPDATE CURRENT_TIMESTAMP
CREATE TRIGGER groups_update_time AFTER UPDATE ON groups
BEGIN
    UPDATE groups SET updated_at = strftime('%s','now') WHERE id = NEW.id;
END;
```

---

## 总结建议

| 方案 | 适合吗 | 推荐度 |
|------|--------|--------|
| **纯 JDBC + 轻量 Repository** | ✅ 适合 NexusChat（9 张表、SQL 简单、桌面应用） | ⭐⭐⭐⭐⭐ |
| **MyBatis-Plus 只用 core** | ⚠️ 能用但有适配成本，收益不明显 | ⭐⭐ |
| **MyBatis-Plus + Spring Boot** | ❌ 不推荐，Spring Boot 对 JavaFX 桌面应用太重 | ⭐ |

**我的建议：Phase 1 先用纯 JDBC 手写 Repository**，等 Phase 3 群聊加进来、SQL 真的变复杂了（比如群聊消息的多条件查询、分页、按群主过滤等），再评估要不要换。现在引入 MyBatis-Plus 是过早优化。

你倾向哪种？

---

**User**

Jpa呢

**Assistant**

**JPA + SQLite 能跑，但同样不是 NexusChat 的最优选择。** 三档对比说清楚：

## JPA（Hibernate）+ SQLite 兼容性

| 维度 | 状态 |
|------|------|
| Hibernate Core + SQLite Dialect | ✅ 有官方 SQLiteDialect（hibernate-community 维护） |
| 标准 CRUD（save / findById / findAll / delete） | ✅ 直接可用 |
| @Query 自定义 SQL | ⚠️ 注意 SQLite 语法（LIMIT 不支持 offset 的某些写法、不支持 JOIN 超过 64 张表等） |
| 自动 DDL（hibernate.hbm2ddl.auto） | ⚠️ SQLite 对 ALTER TABLE 支持极差（只能 ADD COLUMN），改字段只能删表重建 |
| 批量操作（saveAll / deleteAll） | ✅ 可用 |
| 复杂映射（@ManyToMany / @OneToMany cascade） | ⚠️ 级联删除要小心 SQLite 外键约束默认关闭，得手动 `PRAGMA foreign_keys = ON` |

## 三档对比：纯 JDBC vs JPA vs MyBatis-Plus

| 对比项 | 纯 JDBC | JPA (Hibernate) | MyBatis-Plus |
|--------|---------|-----------------|-------------|
| **依赖引入** | `sqlite-jdbc` 一个包 | `hibernate-core` + `hibernate-community-dialects` + `sqlite-jdbc` | `mybatis-plus-core` + `sqlite-jdbc` |
| **额外 jar 体积** | ~8MB | ~30MB（Hibernate 本身很重） | ~15MB |
| **SQLite 适配成本** | 零（直接写 SQL） | 中（方言 + 外键 PRAGMA + DDL 限制） | 低（DbType.SQLITE 枚举内置） |
| **Entity 注解负担** | 无 | 重（@Entity / @Table / @Column / @Id / @GeneratedValue...） | 中（@TableName / @TableId） |
| **学习成本** | 会 SQL 就行 | 高（HQL / JPQL / Entity 生命周期 / flush 时机 / 一级缓存） | 中（BaseMapper / ServiceImpl 体系） |
| **调试体验** | 直接看 SQL | SQL 被 Hibernate 生成，要开 `show_sql=true` 才能看 | 和 JDBC 差不多 |
| **性能** | 最快（无中间层） | 略慢（Session flush 有开销，但一般感知不到） | 介于两者之间 |
| **我们的 9 张表代码量** | ~800-1000 行 | Entity + Repository 约 ~600 行（省了写 SQL） | Mapper + Service 约 ~400 行 |
| **复杂 SQL 灵活性** | 100% | HQL 不够用要写原生 SQL，绕 | 原生 XML 或 @Select 注解都行 |

## NexusChat 的场景分析

```
9 张表
  ├── 简单 CRUD（contacts / app_config / contact_groups）
  ├── 带条件查询（messages 按 conversation_id + timestamp 分页）
  ├── 多表关联（会话列表要 JOIN conversations + contacts + groups）
  └── 群聊选举写入（group_leader_ledger 高频 insert）
```

**SQLite 对 JPA 的几个痛点在我们这里会放大：**

| 痛点 | 对我们的影响 |
|------|------------|
| **外键默认关闭** | 我们大量用 ON DELETE CASCADE（删好友 → 自动删 membership），每个连接都要 `PRAGMA foreign_keys = ON`，Hibernate 配置里容易忘 |
| **SQLite 不支持 ALTER COLUMN** | 每次改表结构（比如 contacts 加个 avatar_hash 字段），Hibernate 的 hbm2ddl=update 会失败，得手动迁移 |
| **Hibernate 的 Session 管理** | 桌面应用不是 Web 请求-响应模型，Session 生命周期得自己手动控制，不像 Spring Boot @Transactional 自动管理 |
| **LazyLoading 在 JavaFX 里** | 群成员列表 @OneToMany(fetch=LAZY)，在 JavaFX UI 线程访问会触发 LazyInitializationException，容易踩坑 |

## 推荐方案对比

| 方案 | 引入依赖 | 9 张表代码量 | 适配成本 | 推荐度 |
|------|---------|------------|---------|--------|
| **纯 JDBC + 手写 Repository** | sqlite-jdbc | ~1000 行 | 零 | ⭐⭐⭐⭐⭐ |
| **MyBatis-Plus (core)** | mybatis-plus-core + sqlite-jdbc | ~400 行 | 低（DbType.SQLITE 内置） | ⭐⭐⭐⭐ |
| **JPA (Hibernate)** | hibernate-core + dialect + sqlite-jdbc | ~600 行 | 中（PRAGMA + DDL + Session） | ⭐⭐ |
| **MyBatis (原生)** | mybatis + sqlite-jdbc | ~600 行（要写 XML） | 零 | ⭐⭐⭐ |

## 如果你倾向用 ORM

**MyBatis-Plus 比 JPA 更适合 NexusChat**，原因：

1. MyBatis-Plus 的 SQLite 方言是**一级内置**（`DbType.SQLITE`），分页、自动填充直接配置
2. MyBatis-Plus 不强绑定 Spring Boot，有 `mybatis-plus-core` 纯 Java 版本
3. 复杂 SQL 写 XML 原生 SQL 就行，不受 HQL 限制
4. 不像 JPA/Hibernate 那样有 Session 生命周期和 LazyLoading 的包袱

**JPA 的优势场景是复杂业务、多表关联、需要对象图导航**——NexusChat 的数据库层完全用不到这些能力。

---

## 我的最终建议

NexusChat 的 9 张表数据模型已经稳定了（不会频繁改表结构），**纯 JDBC + 手写 Repository 是最合适的**：

- 零框架开销
- 零 SQLite 适配问题
- SQL 完全透明，调试最方便
- 1000 行左右的 Repository 代码对 Phase 1 来说可控

如果后面 Phase 3 群聊上了，SQL 真的变复杂了（比如会话列表那个多表 JOIN 分页查询），**可以单独把复杂那两张表（conversations / messages）换成 MyBatis-Plus，其他保持 JDBC**，两种方式混用完全没问题。

你怎么看？还是说你对某个框架更熟悉，想直接用？

---

**User**

那就纯 jdbc，Java25 应该是支持 String slqStr = """
                select * from dual
                where 1=1
                """; 使用这中方式统一sql变量

**Assistant**

好，Java 25 Text Blocks（JEP 378）从 Java 15 就正式 GA 了，25 里完全支持。而且 Java 25 还有 **String Templates（JEP 459，预览特性）**，配合 JDBC 写 SQL 比 PreparedStatement 更优雅。让我把这个方案落进文档。

好，在 storage 包下面补一个 SqlConstants.java 并用 Text Blocks + String Templates 的方式举例：

现在在 §6.2 核心类职责后面补一个 §6.3 SQL 管理方案，集中写 Text Blocks + String Templates 的用法：

后面的章节编号也要同步 +1：

看到有几个编号错位（§8 好友管理下面的子节写成 7.1/7.2，还有两个 §8）。快速修正：

现在修正重复的 §8（文件/图片传输应是 §9）和后续章节：

后续章节全部 +1：

后面 9.x 子节全要改。用批量替换：

快速验证编号是否正确：

章节编号 1-14 连续无跳号了。完美。

---

## ✅ 纯 JDBC + Text Blocks 方案已落档

### 新增 ARCHITECTURE.md §7 SQL 管理方案

完整章节覆盖：

| 小节 | 内容 |
|------|------|
| **7.1 技术选型** | 纯 JDBC + Text Blocks（Text Blocks Java 15+ 正式 GA） + String Templates（Java 25 预览） + PreparedStatement 防注入 |
| **7.2 SqlConstants.java** | 完整示例：contacts / conversations / messages 的多行 SQL 常量，Text Blocks 格式对齐 |
| **7.3 Repository 用法** | ContactRepository 完整示例：PreparedStatement + try-with-resources + ResultSet → Entity 映射 |
| **7.4 动态 SQL** | 两种方式对比：String Templates（有注入风险）vs StringBuilder 拼结构 + setObject 绑值（推荐） |
| **7.5 DatabaseManager** | 单例 Connection + WAL/foreign_keys PRAGMA + initSchema 建表 |
| **7.6 方案总结** | 一张表汇总所有环节的做法 |

### 关键约束写进文档

- ✅ SQLite 的 `foreign_keys` PRAGMA **每次新连接必须执行**（默认关闭，否则 ON DELETE CASCADE 不生效）
- ✅ 所有外部输入**一律走 PreparedStatement 绑定**，绝不拼进 SQL 字符串
- ✅ Text Blocks 三引号后换行是内容的一部分，SQL 末尾不能有多余空白
- ✅ 动态查询用 StringBuilder 拼**结构**（WHERE / AND / ORDER BY），动态值走 `setObject()`

### storage 包结构更新

```
storage/
├── DatabaseManager.java          -- SQLite 连接管理 + PRAGMA + initSchema
├── SqlConstants.java             -- 所有 SQL 常量（Text Blocks，集中管理）
├── ContactRepository.java
├── ConversationRepository.java
├── MessageRepository.java
├── FileTransferRepository.java
├── ContactGroupRepository.java
├── GroupRepository.java
├── GroupMemberRepository.java
├── GroupLeaderLedgerRepository.java
├── AppConfigRepository.java
└── FileStore.java
```

### 文档完整度检查

| 文档 | 章节数 | 编号 | 状态 |
|------|--------|------|------|
| ARCHITECTURE.md | 1-14 | 连续正确 | ✅ |
| DESIGN_GOALS.md | - | - | ✅ 无编号问题 |
| DATABASE.md | 1-7 | 独立文档 | ✅ |

---

文档体系现在有 **3 份文档 + 14 个架构章节 + 10 张数据库表 + 完整 SQL 管理方案**，应该彻底稳定了。

准备好开 Phase 1 骨架了吗？还是还有其他想调整的？

---

