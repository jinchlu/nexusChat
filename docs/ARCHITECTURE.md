# NexusChat 架构设计文档

> 版本：v1.0
> 最后更新：2026-11-05
> 关联文档：[DESIGN_GOALS.md](DESIGN_GOALS.md)（设计目标、功能清单、边界条件）

---

## 1. 项目定位

**NexusChat** 是一款运行在局域网内的纯点对点聊天工具，无需任何中心化服务器，所有通信直接在终端之间建立，用户数据（好友列表、聊天记录）仅存储在各自本地，无云端依赖、无数据泄露风险。

### 核心设计原则

| 原则 | 说明 |
|------|------|
| **纯 P2P，无中心** | 不存在服务端进程，每个节点既是 Server 也是 Client |
| **隐私优先** | 不广播、不自动发现，好友只能通过手动添加 IP:Port 建立 |
| **本地主权** | 好友列表、聊天记录、传输缓存全部本地持久化 |
| **渐进增强** | 先跑通单聊 MVP，再文件图片，再群聊，再稳健性 |

### 技术栈

| 分类 | 选型 | 说明 |
|------|------|------|
| JDK | **Java 25** | 最新 LTS，支持虚拟线程、结构化并发、Pattern Matching |
| UI 框架 | **JavaFX 25** | FXML + CSS，声明式界面 |
| 网络 I/O | `java.net.Socket` + 虚拟线程 | 虚拟线程让阻塞式 I/O 足够高效，无需 NIO 复杂度 |
| 序列化 | 二进制帧 + JSON（Payload） | 控制帧二进制高效，文本消息 JSON 易调试 |
| 构建工具 | **Maven** | pom.xml 单文件，JavaFX plugin + SQLite + SLF4J |
| 日志 | SLF4J + Logback | |
| 本地存储 | SQLite (JDBC) | 单文件数据库，零配置 |
| JSON | Jackson | |

---

## 2. 系统架构

### 2.1 整体分层

```
┌─────────────────────────────────────────────────────────────┐
│                        JavaFX UI 层                         │
│  (FXML / CSS / Controller)                                    │
│  聊天窗口 · 联系人列表 · 文件对话框 · 设置面板                  │
├─────────────────────────────────────────────────────────────┤
│                       业务协调层                              │
│  ChatService · ContactManager · HistoryService · FileManager  │
│  消息路由 · 会话管理 · 状态同步 · 任务编排                      │
├─────────────────────────────────────────────────────────────┤
│                       网络协议层                              │
│  P2PNode · FrameCodec · ConnectionManager · PeerHandshake     │
│  TCP 连接管理 · 帧编解码 · 握手 · 心跳保活                     │
├─────────────────────────────────────────────────────────────┤
│                       本地存储层                              │
│  SQLite (JDBC) · FileStore                                    │
│  好友列表 · 聊天记录 · 传输元数据 · 临时文件                    │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 节点角色

每个 NexusChat 节点在启动时：

1. 自动在本地监听端口（默认 `15000`，可配置）上启动 TCP ServerSocket
2. 同时作为客户端，可以主动连接任何已知的 Peer IP:Port
3. **先发起连接的一方角色为 Client，被连接方角色为 Server**
4. 建立双向通信后，双方角色对上层透明，任何一方都可以发消息

```
  Node A (Client)                    Node B (Server)
       │                                   │
       │─────── TCP Connect ───────────▶│
       │                                   │───── 接受连接
       │                                   │
       │─────── HELLO (握手) ──────────▶│
       │                                   │
       │◀────── HELLO_ACK (握手响应) ───│
       │                                   │
       │══════ 双向通信通道建立 ═══════▶│
       │                                   │
```

**关键约束**：双方都各自监听固定端口，这样谁先连接取决于谁主动发起，角色可动态切换。两个节点间只保持 **一条** TCP 连接（避免环路）。

### 2.3 连接生命周期状态机

```
         ┌──────────┐
         │ DISCONNECT│ ◀──── 初始/断开
         └────┬─────┘
              │ 发起连接 / 接受连接
              ▼
         ┌──────────┐
         │  CONNECTING │
         └────┬─────┘
              │
         握手成功
              │
              ▼
         ┌──────────┐     超时/异常     ┌──────────┐
         │  CONNECTED │──────────────▶│ DISCONNECT│
         └────┬─────┘                  └──────────┘
              │
         心跳超时
              │
              ▼
         ┌──────────┐
         │ DISCONNECT│
         └──────────┘
```

---

## 3. 通信协议

### 3.1 帧格式

TCP 是字节流，必须自定义帧边界。采用固定头 + 变长 Payload 的设计：

```
  Byte Offset:  0    1    2    3    4    5    6    7    8    ...
               ┌────┬────┬────┬────┬──────────────────┬─────────┐
               │ Magic │ Type │  Payload Length (int32)│ Payload  │
               │ 2B   │ 2B   │     4B (大端)            │  N B    │
               └────┴────┴────┴────┴──────────────────┴─────────┘
```

| 字段 | 长度 | 说明 |
|------|------|------|
| Magic | 2B | 固定魔数 `0xCAFE`，用于快速校验、防止脏数据 |
| Type | 2B | 消息类型枚举值 |
| Length | 4B | Payload 的字节数（**不包含 Magic + Type + Length 头本身**），大端序 |
| Payload | NB | 具体消息内容，根据 Type 解析 |

### 3.2 消息类型

```java
public enum MessageType {
    // ── 连接管理 ──
    HELLO(0x0001),          // 握手请求（携带自己的昵称、端口）
    HELLO_ACK(0x0002),      // 握手响应（接受/拒绝码 + 对方昵称 + 拒绝原因）
    HEARTBEAT(0x0003),      // 心跳包（Payload 为空）
    BYE(0x0004),            // 主动断开
    ACK(0x0005),            // **通用确认**：TEXT / IMAGE / GROUP_MESSAGE 等需要确认的消息
                                // Payload: {"msgId": "uuid", "timestamp": ..., "status": "OK"}

    // ── 好友管理 ──
    FRIEND_REQUEST(0x0010), // 添加好友请求
    FRIEND_RESPONSE(0x0011), // 好友请求响应（接受/拒绝）
    FRIEND_LIST_REQ(0x0012), // 请求对方好友列表（可选）
    FRIEND_LIST_RESP(0x0013),// 好友列表响应

    // ── 聊天消息 ──
    TEXT(0x0020),           // 纯文本消息
    IMAGE(0x0021),          // 图片（缩略图 + 原图分块）
    FILE_META(0x0030),      // 文件元数据
    FILE_CHUNK(0x0031),     // 文件数据分片（**Payload 格式：chunkIndex 4字节大端 + 纯二进制**，不做 base64）
    FILE_ACK(0x0032),       // 文件分片确认（Payload: {"transferId": "...", "chunkIndex": 3, "status": "OK"}）
    FILE_DONE(0x0033),      // 文件传输完成
    CANCEL_TRANSFER(0x0034) // 取消传输

    // ── 群聊消息类型详见 §10.8（Phase 3 扩展）──
}
```

### 3.3 消息 Payload 结构

**统一约定（本协议所有 Payload 通用）**：
- 编码：JSON (UTF-8)，例外：FILE_CHUNK 用**纯二进制**（见下文）
- **时间戳字段一律是 Unix 毫秒**（`System.currentTimeMillis()`），无例外
- **UUID 一律是标准格式**（`8-4-4-4-12` 连字符小写），如 `"f47ac10b-58cc-4372-a567-0e02b2c3d479"`
- 可选字段缺失时 JSON 里**不出现该 key**，不要传 `null`

**HELLO** (0x0001)：
```json
{
  "nickname": "Alice",
  "listenPort": 15000,
  "version": "0.1.0",
  "deviceId": "8f3a2c1b",
  "timestamp": 1730790000000
}
```

**HELLO_ACK** (0x0002)：
```json
// 接受
{
  "status": "ACCEPTED",
  "nickname": "Bob",
  "deviceId": "12ab34cd",
  "timestamp": 1730790000100
}

// 拒绝
{
  "status": "REJECTED",
  "rejectCode": 1,
  "reason": "version mismatch: mine=0.2.0, yours=0.1.0",
  "timestamp": 1730790000100
}
```

**HELLO_ACK 拒绝码枚举**：

| rejectCode | 含义 | 处理方行为 |
|-----------|------|-----------|
| 1 | **版本不兼容** | 弹窗提示"对方版本太旧/太新，请升级"，断开连接 |
| 2 | **deviceId 黑名单** | 弹窗提示"对方已将你拉黑"，断开连接 |
| 3 | **deviceId 已在连接中** | 已有一条连接，发 BYE 让双方复用旧连接（见 §3.6 双向连接冲突解决） |
| 4 | **握手超时** | 等待 HELLO_ACK 超过 5 秒 → 主动断连，重连最多 3 次 |
| 255 | **内部错误** | 弹窗提示"连接对方时发生未知错误"，断开连接 |

**通用 ACK** (0x0005)：
```json
{
  "msgId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "timestamp": 1730790000100,
  "status": "OK"
}
```
用于 TEXT / IMAGE / GROUP_MESSAGE 等需要确认的消息。FILE_CHUNK 有自己的 FILE_ACK (0x0032)。

**TEXT** (0x0020)：
```json
{
  "msgId": "uuid-string",
  "content": "你好",
  "timestamp": 1730790000000,
  "senderDeviceId": "8f3a2c1b"
}
```

**FILE_META** (0x0030)：
```json
{
  "transferId": "uuid-string",
  "fileName": "report.pdf",
  "fileSize": 2048576,
  "mimeType": "application/pdf",
  "sha256": "e3b0c44298fc1c149afbf4c8996fb924...",
  "chunkSize": 65536,
  "totalChunks": 32
}
```

**FILE_CHUNK** (0x0031) — **纯二进制格式，不做 JSON 编码**：
```
┌──────────────────┬──────────────────────────────────┐
│ chunkIndex (4B)  │         二进制数据 (N 字节)       │
│ 大端序 uint32    │                                  │
└──────────────────┴──────────────────────────────────┘

总 Payload 长度 = 4 + chunkSize
chunkIndex 从 0 开始，最大 totalChunks - 1
```
**为什么不用 JSON + base64？** base64 膨胀 33%，一个 10MB 文件要多传 3.3MB，完全没必要。4 字节头 + 纯二进制是最省的格式。

**FILE_ACK** (0x0032)：
```json
{
  "transferId": "uuid-string",
  "chunkIndex": 3,
  "status": "OK",
  "timestamp": 1730790000200
}
```
status = "OK" 成功 / "REJECTED"（收到但 hash 不对，要求重发这一块）

### 3.4 粘包处理流程

```
SocketInputStream
       │
       ▼
┌─── 读取 8 字节头 ─────────────────────────┐
│ 1. 读 2B Magic → 校验 0xCAFE              │
│ 2. 读 2B Type  → 映射为 MessageType 枚举  │
│ 3. 读 4B Length → 解析为 int (大端)        │
└──────────────┬─────────────────────────────┘
               │
               ▼
┌─── 读取 Payload ──────────────────────────┐
│ 循环读取，直到累计字节数 == Length         │
│ (TCP 可能多次返回才凑够 N 字节)            │
└──────────────┬─────────────────────────────┘
               │
               ▼
       交给业务层解析处理
```

### 3.5 心跳与保活

```
每 30 秒 → 主动发送 HEARTBEAT (空 Payload)
         → 重置对端 "最后活跃时间"

超时判断：
- 若 90 秒内未收到任何来自对端的数据
- 判定连接已断，触发重连或标记离线
```

### 3.6 双向同时连接冲突解决

**问题**：A 主动连接 B 的同时，B 也在主动连接 A → 两条 TCP 连接并存。

**统一解决规则（基于双方监听端口比较）**：
```
双方在 HELLO 里都带上 listenPort（见 §3.3 HELLO Payload）

收到对方的 HELLO 后，比较：
  我的 listenPort  <  对方的 listenPort
    → 我是"主动方"，关闭我刚才发起的那条连接
    → 保留被 ServerSocket accept 的那条被动连接

  我的 listenPort  >  对方的 listenPort
    → 我是"被动方"，保留当前这条连接（对方会关他自己发起的那条）

  我的 listenPort  == 对方的 listenPort（极小概率，一般不会）
    → 用 deviceId 字典序决定：deviceId 小的那个保留被动连接，关掉主动连接
```

**实现流程（两边都执行相同逻辑）**：
```java
// P2PNode 收到 HELLO 时
void onHello(Socket incoming, HelloPayload helloPayload) {
    int myPort       = this.listenPort;
    int peerPort     = helloPayload.listenPort;
    String myDevice  = this.deviceId;
    String peerDevice = helloPayload.deviceId;

    // 已经有一条到这个 deviceId 的连接了
    if (existingConnection != null) {
        boolean keepThisOne;
        if (myPort != peerPort) {
            keepThisOne = (myPort > peerPort);  // 端口大的保留被动连接
        } else {
            keepThisOne = myDevice.compareTo(peerDevice) > 0;
        }
        if (keepThisOne) {
            existingConnection.sendBye();      // 关掉旧连接
            existingConnection.close();
            registerNewConnection(incoming);
        } else {
            incoming.close();                  // 关掉刚收到的这条
        }
    } else {
        registerNewConnection(incoming);
    }
}
```

**为什么用端口比较？**
- 两端执行完全相同的算法 → 结果必然一致 → 不会出现"两边都关了"或"两边都保留"
- 用户可以通过配置不同监听端口来影响角色

---

## 4. 线程模型

### 4.1 Java 25 虚拟线程

利用 Java 25 的虚拟线程（Virtual Threads）为每个连接分配独立线程，阻塞式 I/O 即可获得高并发能力，无需手写 Reactor 模式。

```
JavaFX Application Thread                    虚拟线程池 (Executors.newVirtualThreadPerTaskExecutor())
┌─────────────────────────┐                 ┌─────────────────────────────────────────────────────────┐
│                          │                 │                                                      │
│  [UI 组件]  ◀── runLater ─── 消息入队 ──── │  Thread-1:  ServerSocket.accept() 循环 (接受新连接)    │
│       ▲                  │                 │  Thread-2:  Peer-A SocketInputStream 读循环          │
│       │                  │                 │  Thread-3:  Peer-B SocketInputStream 读循环          │
│  用户操作 ──▶ ChatService │                │  Thread-N:  文件传输写循环                              │
│       │                  │                 │  Thread-M:  心跳定时任务                               │
│       ▼                  │                 │                                                      │
│  网络发送 ────────────────┼───────────────▶ │  → PeerX SocketOutputStream.write()                  │
│                          │                 │                                                      │
└─────────────────────────┘                 └─────────────────────────────────────────────────────────┘
```

### 4.2 线程安全规则

| 操作 | 执行线程 | 方式 |
|------|---------|------|
| UI 组件读写 | **必须** JavaFX Thread | `Platform.runLater(Runnable)` |
| Socket 读写 | 虚拟线程 | 直接阻塞调用 |
| 数据库操作 | 虚拟线程 | 直接调用，SQLite JDBC 本身线程安全 |
| 业务状态变更 | 虚拟线程 → 切回 UI | 变更后 `Platform.runLater` 更新显示 |
| 文件读写 | 虚拟线程 | 直接 NIO |

**禁止** 在后台线程直接访问任何 `javafx.scene` 下的类。

### 4.3 消息传递模式

```
后台网络线程收到消息
       │
       ▼
  解析帧 → 构造业务对象 (ChatMessage / ContactInfo / FileTransfer...)
       │
       ▼
  Platform.runLater(() -> {
      // 在 UI 线程更新控件
      chatArea.appendMessage(message);
      contactList.updatePeerStatus(peerId, ONLINE);
      ...
  })
```

---

## 5. 本地存储设计

> **完整数据库设计（表结构、E-R 图、业务查询、状态流转）见 [DATABASE.md](DATABASE.md)**，以下仅保留文件存储结构。

### 5.1 文件存储结构（不变）

```
~/.nexuschat/
├── nexuschat.db                    -- SQLite 数据库（见 DATABASE.md）
├── images/                         -- 接收/发送的图片
│   ├── 2026-11-05_xxxx.jpg
│   └── ...
├── files/                          -- 接收/发送的文件
│   ├── report_20261105.pdf
│   └── ...
├── temp/                           -- 传输中的临时文件
│   ├── transfer_xxx.part
│   └── ...
└── device_id                       -- 本机 deviceId 指纹（首次启动生成，终身不变）
```

---

## 6. 模块设计

### 6.1 包结构

```
com.nexuschat
├── app/                    -- 应用入口
│   ├── NexusChatApp.java      -- Application 启动类
│   └── AppConfig.java         -- 全局配置（端口、路径等）
│
├── network/                -- 网络协议层
│   ├── P2PNode.java           -- 本节点管理（监听端口、连接管理）
│   ├── PeerConnection.java    -- 单个对端连接（Socket 封装）
│   ├── FrameCodec.java        -- 帧编解码（byte[] ↔ Frame）
│   ├── MessageFrame.java      -- 帧数据对象
│   ├── PeerHandshake.java     -- 握手逻辑
│   ├── HeartbeatService.java  -- 心跳定时任务
│   └── ConnectionManager.java -- 所有活跃连接的注册/查找
│
├── protocol/               -- 协议定义
│   ├── MessageType.java       -- 消息类型枚举
│   ├── HelloPayload.java      -- 握手 Payload
│   ├── TextPayload.java      -- 文本消息 Payload
│   ├── FileMetaPayload.java  -- 文件元数据 Payload
│   └── ...                    -- 其他 Payload 类
│
├── service/                -- 业务协调层
│   ├── ChatService.java       -- 聊天核心服务（消息路由）
│   ├── ContactManager.java    -- 好友增删查 + 请求处理
│   ├── HistoryService.java    -- 聊天记录查询/持久化
│   ├── FileManager.java       -- 文件分块传输编排
│   └── GroupService.java      -- 群聊管理（后续）
│
├── storage/                -- 本地存储层（纯 JDBC，SQLite）
│   ├── DatabaseManager.java   -- SQLite 连接管理 + PRAGMA 初始化
│   ├── SqlConstants.java      -- **所有 SQL 常量集中定义**（Text Blocks）
│   ├── ContactRepository.java -- contacts 表 CRUD
│   ├── MessageRepository.java -- messages 表 CRUD
│   ├── ConversationRepository.java -- conversations 表 CRUD + 会话列表联表查询
│   ├── FileTransferRepository.java
│   ├── ContactGroupRepository.java
│   ├── GroupRepository.java
│   ├── GroupMemberRepository.java
│   ├── GroupLeaderLedgerRepository.java
│   ├── AppConfigRepository.java
│   └── FileStore.java         -- 文件系统读写
│
├── ui/                     -- JavaFX 界面层
│   ├── MainController.java    -- 主窗口控制器
│   ├── ContactListController.java
│   ├── ChatAreaController.java
│   ├── FileTransferController.java
│   ├── dialogs/               -- 弹窗
│   │   ├── AddContactDialog.java
│   │   ├── FriendRequestDialog.java
│   │   └── SettingsDialog.java
│   └── styles/                -- CSS 样式
│       └── main.css
│
└── util/                   -- 工具类
    ├── DeviceIdGenerator.java  -- 本机唯一标识生成
    ├── JsonUtil.java           -- Jackson 封装
    └── NetworkUtil.java        -- 本机 IP 枚举
```

### 6.2 核心类职责

#### P2PNode（网络入口）
```java
public class P2PNode {
    // 启动：开启 ServerSocket 监听
    void start(int listenPort);

    // 主动连接对端
    CompletableFuture<PeerConnection> connect(String ip, int port);

    // 运行时修改监听端口（关闭旧 ServerSocket → 绑定新端口）
    // 已建立的 PeerConnection 不受影响，只是新入站连接走新端口
    void changeListenPort(int newPort) throws IOException;

    // 获取当前监听的端口（UI 状态栏展示用）
    int getListenPort();

    // 关闭所有连接
    void shutdown();

    // 获取本机 deviceId
    String getDeviceId();
}
```

**changeListenPort 内部逻辑**：
```java
void changeListenPort(int newPort) throws IOException {
    // 1. 关闭旧 ServerSocket（不再接受新入站连接，但已有 PeerConnection 不受影响）
    ServerSocket old = this.serverSocket;
    this.serverSocket = null;           // 先置空，防止并发 accept
    old.close();

    // 2. 在新端口上创建 ServerSocket
    this.serverSocket = new ServerSocket(newPort);
    this.listenPort = newPort;

    // 3. 重新启动 accept 循环（新 ServerSocket 绑定新端口）
    startAcceptLoop();

    // 4. 触发事件通知 UI 更新状态栏 + 保存到 config.properties
    EventBus.fire(new ListenPortChangedEvent(newPort));
}
```

**技术边界**：
- 已建立的长连接（PeerConnection）**不会断开** —— ServerSocket 只负责 accept，accept 出来的 Socket 是独立对象，关掉 ServerSocket 不影响已 accept 的 Socket
- 对端在握手时拿到的 `listenPort` 是旧值 —— 他们之后如果重连需要手动更新我们的新端口（或我们主动 HELLO 广播自己换了端口，后续可加）
- 如果新端口被占用 → 抛出 `IOException` → UI 弹窗提示"端口 X 已被占用，请换一个"，旧 ServerSocket 保持不变
- 端口修改后写入 `~/.nexuschat/config.properties`，下次启动直接读新值

#### PeerConnection（单连接）
```java
public class PeerConnection {
    String getDeviceId();
    String getNickname();
    String getRemoteIp();
    int getRemotePort();
    ConnectionState getState();  // DISCONNECT/CONNECTING/CONNECTED

    void send(MessageFrame frame);
    void close();

    // 注册消息回调
    void setOnMessage(Consumer<MessageFrame> handler);
    void setOnDisconnect(Runnable handler);
}
```

#### ChatService（消息路由中心）
```java
public class ChatService {
    // 发送文本
    void sendTextMessage(String contactId, String content);

    // 发送文件（触发 FileManager 编排）
    void sendFile(String contactId, Path filePath);

    // 发送图片
    void sendImage(String contactId, Path imagePath);

    // 添加好友请求
    void requestAddFriend(String ip, int port, String nickname);

    // 响应好友请求
    void respondFriendRequest(String deviceId, boolean accept);
}
```

---

## 7. SQL 管理方案（纯 JDBC + Text Blocks + String Templates）

### 7.1 技术选型

| 项 | 选择 | 说明 |
|----|------|------|
| 持久层方案 | **纯 JDBC** | 不引 MyBatis-Plus / JPA，9 张表 + SQLite 用不到 ORM 的重量 |
| SQL 写法 | **Text Blocks**（JEP 378，Java 15+ 正式 GA） | 多行 SQL 常量类集中管理，可读性极佳 |
| 动态拼接 | **String Templates**（JEP 459，Java 25 预览特性） | `${expr}` 嵌入参数，比手动 `String.format` 优雅 |
| 参数绑定 | `PreparedStatement` 占位符 `?` | 动态值走 `ps.setXxx(n, value)`，防 SQL 注入 |

### 7.2 SqlConstants.java — 所有 SQL 集中管理

**为什么集中管理？**
- SQL 是 Repository 层最容易出错的地方，集中在一个文件里全局搜索方便
- 改表结构时，SQL 常量一处修改，所有 Repository 自动生效
- Text Blocks 让多行 SQL 天然格式化，IDE 里一眼看清缩进

```java
// storage/SqlConstants.java
public final class SqlConstants {
    private SqlConstants() {}

    // ── contacts ──
    public static final String CONTACTS_CREATE = """
        CREATE TABLE IF NOT EXISTS contacts (
            id              TEXT PRIMARY KEY,
            device_id       TEXT NOT NULL UNIQUE,
            nickname        TEXT NOT NULL,
            remark          TEXT,
            ip_address      TEXT,
            port            INTEGER,
            avatar_path     TEXT,
            is_blocked      INTEGER NOT NULL DEFAULT 0,
            is_online       INTEGER NOT NULL DEFAULT 0,
            last_seen_at    INTEGER,
            added_at        INTEGER NOT NULL
        )""";

    public static final String CONTACTS_INSERT = """
        INSERT INTO contacts
        (id, device_id, nickname, remark, ip_address, port, added_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)""";

    public static final String CONTACTS_FIND_BY_ID = """
        SELECT * FROM contacts WHERE id = ?""";

    public static final String CONTACTS_FIND_BY_DEVICE_ID = """
        SELECT * FROM contacts WHERE device_id = ?""";

    public static final String CONTACTS_FIND_ALL_ONLINE = """
        SELECT * FROM contacts
        WHERE is_online = 1 AND is_blocked = 0
        ORDER BY nickname""";

    // ── conversations ──
    public static final String CONV_LIST_WITH_PREVIEW = """
        SELECT c.id, c.type, c.last_message_preview, c.last_message_ts,
               c.unread_count, c.is_pinned,
               CASE c.type
                   WHEN 'PRIVATE' THEN ct.nickname
                   WHEN 'GROUP'   THEN g.name
               END AS display_name
        FROM conversations c
        LEFT JOIN contacts ct ON c.type = 'PRIVATE' AND c.contact_id = ct.id
        LEFT JOIN groups g    ON c.type = 'GROUP'   AND c.group_id   = g.id
        WHERE (c.type = 'PRIVATE' AND ct.id IS NOT NULL)
           OR (c.type = 'GROUP'   AND g.status != 'DELETED')
        ORDER BY c.is_pinned DESC, c.last_message_ts DESC""";

    // ── messages ──
    public static final String MSG_INSERT = """
        INSERT INTO messages
        (id, conversation_id, sender_device_id, sender_nickname,
         msg_type, content, media_path, media_size, media_mime_type,
         transfer_id, timestamp, local_received_at, direction, status)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    public static final String MSG_LOAD_HISTORY = """
        SELECT * FROM messages
        WHERE conversation_id = ?
        ORDER BY timestamp ASC
        LIMIT ? OFFSET ?""";

    // ... 其他 7 张表的 SQL
}
```

### 7.3 Repository 内部用法

```java
// storage/ContactRepository.java
public class ContactRepository {

    private final Connection conn;

    public ContactRepository(Connection conn) {
        this.conn = conn;
    }

    public Optional<Contact> findById(String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SqlConstants.CONTACTS_FIND_BY_ID)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    public void insert(Contact c) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SqlConstants.CONTACTS_INSERT)) {
            ps.setString(1, c.getId());
            ps.setString(2, c.getDeviceId());
            ps.setString(3, c.getNickname());
            ps.setString(4, c.getRemark());
            ps.setString(5, c.getIpAddress());
            ps.setInt(6, c.getPort());
            ps.setLong(7, c.getAddedAt());
            ps.executeUpdate();
        }
    }

    // Row mapping: ResultSet → Contact
    private Contact mapRow(ResultSet rs) throws SQLException {
        return new Contact(
            rs.getString("id"),
            rs.getString("device_id"),
            rs.getString("nickname"),
            rs.getString("remark"),
            rs.getString("ip_address"),
            rs.getInt("port"),
            rs.getInt("is_online") == 1,
            rs.getLong("added_at")
        );
    }
}
```

#### MessageRepository 判重示例（ACK 丢了对方重发）

```java
// storage/MessageRepository.java
public class MessageRepository {

    private final Connection conn;

    public MessageRepository(Connection conn) {
        this.conn = conn;
    }

    // INSERT OR IGNORE：利用 messages.id PRIMARY KEY 天然防重
    // 收到重复 msgId（对端重发 ACK 丢了的 TEXT）→ SQLite 静默跳过
    public void insertOrIgnore(Message msg) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SqlConstants.MSG_INSERT_OR_IGNORE)) {
            ps.setString(1, msg.getId());
            ps.setString(2, msg.getConversationId());
            ps.setString(3, msg.getSenderDeviceId());
            ps.setString(4, msg.getSenderNickname());
            ps.setString(5, msg.getMsgType().name());
            ps.setString(6, msg.getContent());
            ps.setString(7, msg.getMediaPath());
            ps.setLong(8, msg.getMediaSize());
            ps.setString(9, msg.getMediaMimeType());
            ps.setString(10, msg.getTransferId());
            ps.setLong(11, msg.getTimestamp());
            ps.setLong(12, msg.getLocalReceivedAt());
            ps.setString(13, msg.getDirection().name());
            ps.setString(14, msg.getStatus().name());
            ps.executeUpdate();  // 返回 1 = 插入成功；返回 0 = 主键冲突被 IGNORE（不是异常！）
        }
    }
}
```

#### SqlConstants 里对应的 SQL

```java
public static final String MSG_INSERT_OR_IGNORE = """
    INSERT OR IGNORE INTO messages
    (id, conversation_id, sender_device_id, sender_nickname,
     msg_type, content, media_path, media_size, media_mime_type,
     transfer_id, timestamp, local_received_at, direction, status)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";
```

**为什么 INSERT OR IGNORE 比 catch 异常好？**
- `INSERT OR IGNORE` 是 SQLite 原生 SQL，**零异常零开销**，返回 `int` 告诉你插没插进去
- 比 `try { insert } catch (SQLException)` 快很多（异常创建开销大）
- `affectedRows == 0` 就是"这条消息我已经存过了"，UI 可以直接丢掉不渲染

### 7.4 动态 SQL 怎么办？

**已确认：打开 Java 25 String Templates（JEP 459，enable-preview）**。pom.xml 里统一配置：

```xml
<!-- pom.xml → maven-compiler-plugin + maven-surefire-plugin 都要加 -->
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <source>25</source>
        <target>25</target>
        <enablePreview>true</enablePreview>  <!-- 打开 Java 25 预览特性 -->
    </configuration>
</plugin>
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <argLine>--enable-preview</argLine>  <!-- 运行测试时也要开 -->
    </configuration>
</plugin>
```

JDBC 动态拼接 SQL 容易出问题（拼错空格、拼错引号）。推荐两种方式，两种都能用（方式 B 更安全）：

#### 方式 A：String Templates（已打开）

```java
// Java 25 enable-preview 已打开，随时可用
public List<Contact> searchContacts(String keyword, boolean onlyOnline) {
    String sql = onlyOnline
        ? STR."""
            SELECT * FROM contacts
            WHERE nickname LIKE '%\{keyword}%' AND is_online = 1
            ORDER BY nickname"""
        : STR."""
            SELECT * FROM contacts
            WHERE nickname LIKE '%\{keyword}%'
            ORDER BY nickname""";
    // ⚠️ 注意：LIKE 里直接嵌 keyword 有 SQL 注入风险
    // 实际应该用 PreparedStatement:
    //   sql = "SELECT * FROM contacts WHERE nickname LIKE ?"
    //   ps.setString(1, "%" + keyword + "%")
}
```

#### 方式 B：硬拼但可控（推荐！）

SQLite 语法简单，我们的动态场景有限（比如"可选的 WHERE 条件"），用简单的 StringBuilder 就够：

```java
public List<Contact> search(SearchFilter filter) throws SQLException {
    StringBuilder sql = new StringBuilder("SELECT * FROM contacts WHERE 1=1");
    List<Object> params = new ArrayList<>();

    if (filter.keyword() != null) {
        sql.append(" AND nickname LIKE ?");
        params.add("%" + filter.keyword() + "%");
    }
    if (filter.onlyOnline()) {
        sql.append(" AND is_online = 1");
    }
    if (filter.excludeBlocked()) {
        sql.append(" AND is_blocked = 0");
    }
    sql.append(" ORDER BY nickname");

    try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
        try (ResultSet rs = ps.executeQuery()) {
            return mapAll(rs);
        }
    }
}
```

**为什么这样写没问题？**
- 所有动态值走 `PreparedStatement.setObject()`，**天然防 SQL 注入**
- 只有"结构"（AND / OR）是硬拼的，不存在注入风险
- 比 String Templates 更安全，也不依赖 Java 25 预览特性

### 7.5 DatabaseManager 初始化 + BusyException 重试装饰器

**为什么要每个线程一个 Connection？** SQLite WAL 模式支持并发读，但两个虚拟线程同时写的时候仍然有竞争。每个线程独立 Connection + `PRAGMA busy_timeout = 30000` + **重试装饰器**三重保险：

```java
// storage/DatabaseManager.java
public class DatabaseManager {

    private static final String DB_PATH = System.getProperty("user.home")
        + "/.nexuschat/nexuschat.db";

    // 每个虚拟线程独享一个 Connection（ThreadLocal）
    // SQLite WAL 模式 + 独立 Connection → 读写并发不阻塞
    private static final ThreadLocal<Connection> CONN_HOLDER = ThreadLocal.withInitial(() -> {
        try {
            Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);
            initPragmas(conn);
            return conn;
        } catch (SQLException e) {
            throw new RuntimeException("SQLite 连接初始化失败", e);
        }
    });

    public static Connection getConnection() {
        return CONN_HOLDER.get();
    }

    private static void initPragmas(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode  = WAL");
            stmt.execute("PRAGMA foreign_keys  = ON");      // SQLite 默认关，必须手动开
            stmt.execute("PRAGMA synchronous   = NORMAL");
            stmt.execute("PRAGMA busy_timeout  = 30000"); // 锁等待 30s
            stmt.execute("PRAGMA cache_size    = -64000");
        }
    }

    // ── SQLite SQLITE_BUSY 重试装饰器 ──────────────────────────────────
    // SQLite 并发写入时，即使 WAL + busy_timeout，极端情况下仍可能抛 BusyException
    // 这个装饰器捕获 busy_timeout 之外的 SQLITE_BUSY，指数退避重试
    // 错误码 5 = SQLITE_BUSY
    private static final int SQLITE_BUSY = 5;
    private static final int MAX_RETRIES = 5;

    /**
     * 执行一个数据库操作，遇到 SQLITE_BUSY 自动重试。
     * 用法：
     *   DatabaseManager.withRetry(() -> {
     *       repo.insert(msg);
     *       return null;
     *   });
     */
    public static <T> T withRetry(SqlSupplier<T> action) throws SQLException {
        int attempt = 0;
        while (true) {
            try {
                return action.get();
            } catch (SQLException e) {
                if (e.getErrorCode() != SQLITE_BUSY || attempt >= MAX_RETRIES) {
                    throw e;  // 不是 Busy 或已到上限 → 直接抛出
                }
                attempt++;
                // 指数退避：50ms → 100ms → 200ms → 400ms → 800ms
                long sleepMs = 50L * (1L << (attempt - 1));
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("重试被中断", ie);
                }
            }
        }
    }

    @FunctionalInterface
    public interface SqlSupplier<T> {
        T get() throws SQLException;
    }

    /**
     * 无返回值版本的重试（void 操作）
     */
    public static void withRetry(SqlRunnable action) throws SQLException {
        withRetry(() -> {
            action.run();
            return null;
        });
    }

    @FunctionalInterface
    public interface SqlRunnable {
        void run() throws SQLException;
    }

    // ── 启动时一次性建表 ──────────────────────────────────────────────
    public static void initSchema() throws SQLException {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            // 10 张表全部执行一次（CREATE TABLE IF NOT EXISTS 幂等）
            stmt.execute(SqlConstants.CONTACTS_CREATE);
            stmt.execute(SqlConstants.CONVERSATIONS_CREATE);
            stmt.execute(SqlConstants.MESSAGES_CREATE);
            stmt.execute(SqlConstants.FILE_TRANSFERS_CREATE);
            stmt.execute(SqlConstants.CONTACT_GROUPS_CREATE);
            stmt.execute(SqlConstants.CONTACT_GROUP_MEMBERSHIP_CREATE);
            stmt.execute(SqlConstants.GROUPS_CREATE);
            stmt.execute(SqlConstants.GROUP_MEMBERS_CREATE);
            stmt.execute(SqlConstants.GROUP_LEADER_LEDGER_CREATE);
        }
    }
}
```

#### Repository 里用 withRetry 的写法

```java
// 业务层调用 → 包一层 withRetry
DatabaseManager.withRetry(() -> {
    messageRepo.insertOrIgnore(newMsg);
    contactRepo.updateOnlineStatus(deviceId, true);
    return null;
});
```

**三层防线**：
| 层级 | 作用 | 配置 |
|------|------|------|
| ① `PRAGMA busy_timeout = 30000` | SQLite 原生锁等待 | 30 秒内尽量自己等 |
| ② WAL 模式 | 读写并发不阻塞 | journal_mode = WAL |
| ③ `withRetry()` 装饰器 | 捕获 escaped SQLITE_BUSY 指数退避 | 最多 5 次，50ms → 800ms |

#### 为什么 ThreadLocal 而不是单例 Connection？

SQLite 的 Connection **不是线程安全的**。单例 Connection 被多个虚拟线程同时调用时，虽然 WAL 模式能防大部分问题，但某些 JDBC 驱动实现内部仍可能出现竞争。ThreadLocal 让每个虚拟线程拿到自己独立的 Connection，彻底安全。代价是 Connection 数量 = 活跃线程数（对我们 10 个以内的连接量来说完全不是问题）。

### 7.6 方案总结

| 环节 | 做法 | 示例 |
|------|------|------|
| **SQL 常量** | Text Blocks + 集中在 `SqlConstants.java` | `public static final String CONTACTS_FIND = """ ... """` |
| **静态 CRUD** | `PreparedStatement` + 占位符 `?` | `ps.setString(1, id)` |
| **动态查询** | `StringBuilder` 拼结构 + `PreparedStatement.setObject()` 绑值 | 安全且可控 |
| **连接管理** | `DatabaseManager.getConnection()` 单例连接 + WAL PRAGMA | 线程安全 + 读写并发 |
| **表初始化** | 启动时执行 9 条 CREATE TABLE（IF NOT EXISTS） | `DatabaseManager.initSchema()` |

**关键约束**：
- 所有外部输入（IP、昵称、搜索关键词）**一律走 PreparedStatement 绑定**，绝不拼进 SQL 字符串
- SQLite 的 `foreign_keys` PRAGMA 必须每次新连接都执行（默认关闭）
- Text Blocks 里写 SQL 注意缩进——三引号之后的换行是内容的一部分，SQL 末尾不能有多余空白

---

## 8. 好友管理流程

### 8.1 添加好友

```
用户在界面点击 "添加好友"
       │
       ▼
弹出对话框，输入对方 IP:Port + 昵称
       │
       ▼
主动连接对方 (P2PNode.connect)
       │
       ▼
建立 TCP 连接 + 握手 (HELLO/HELLO_ACK)
       │
       ▼
发送 FRIEND_REQUEST
       │
       ▼
对方收到 → 弹出确认对话框
       │  (点击接受)
       ▼
对方回复 FRIEND_RESPONSE (accepted=true)
       │
       ▼
双方各自在本地 contacts 表插入一条记录
       │
       ▼
好友添加完成，可发起聊天
```

### 8.2 好友列表的本地性

- **好友列表仅本地维护，不同步** —— A 添加了 B，B 的好友列表里不一定有 A
- 类似 Telegram 的"联系人"机制，但因为是 P2P，双方互为好友后才能正常聊天（由应用层策略控制）
- 后续群聊场景需要"对方同意进群"，此时好友关系可作为前置条件

---

## 9. 文件/图片传输

### 9.1 分块传输策略

```
发送方                              接收方
  │                                    │
  │──── FILE_META ───────────────────▶│  接收元数据 → 显示进度条
  │                                    │
  │──── FILE_CHUNK (chunk 1) ────────▶│  写入 temp/xxx.part
  │◀─── FILE_ACK (chunk 1) ──────────│  
  │                                    │
  │──── FILE_CHUNK (chunk 2) ────────▶│
  │◀─── FILE_ACK (chunk 2) ──────────│
  │         ...                        │
  │                                    │
  │──── FILE_DONE ───────────────────▶│  校验 sha256 → 重命名 .part → 正式文件
  │                                    │
```

**参数**：
- 默认分块大小：64 KB（可根据网络调整为 256 KB / 1 MB）
- 每块收到后必须 ACK，超时 5 秒未收到 ACK 重发该块
- 支持断点续传（后续可加：FILE_RESUME 帧 + 记录已接收块列表）

### 9.2 图片优化

图片在传输前可按以下策略处理：
- 超过 2 MB 的图片自动压缩到 1920px 以内
- 发送方同时生成缩略图（200px），在聊天列表里先显示缩略图，点击查看原图

---

## 10. 群聊设计（后续扩展）

### 10.1 架构模型：群主 = Server，成员 = Client

发起人自动成为 **群主（S）**，其他成员都是 **客户端（C）**。群内所有消息都经群主转发，成员之间不直接通信。

```
                    ┌───────────┐
                    │ 群主 S     │ ← 创建群的人，唯一协调者/转发中枢
                    │ (Node A)   │
                    └──┬──┬──┬──┘
                       │  │  │   各自一条 TCP 长连接（复用单聊的 PeerConnection）
          ┌────────────┘  │  └────────────┐
          ▼               ▼               ▼
     ┌─────────┐    ┌─────────┐    ┌─────────┐
     │ 成员 C1 │    │ 成员 C2 │    │ 成员 C3 │
     │(Node B) │    │(Node C) │    │(Node D) │
     └─────────┘    └─────────┘    └─────────┘
```

**连接数**：N 个节点的群只需要 **N-1 条 TCP 长连接**（群主到每个成员各一条）。

**消息流向**：
```
C1 发群消息 → 发给群主 S  → S 转发给 C1、C2、C3（全员，包括发送方自己）
C2 发群消息 → 发给群主 S  → S 转发给 C1、C2、C3
群主自己发  → 本地产生    → S 转发给所有成员
```

### 10.2 为什么这样设计（Trade-off 分析）

| 维度 | 分析 |
|------|------|
| **实现复杂度** | ⭐ 极低。群逻辑就是群主"收一条 → 遍历转发"，成员只需要能识别 `GROUP_MESSAGE` 帧 |
| **与单聊架构复用** | ✅ 完全复用。成员到群主的 TCP 连接和单聊用同一个 `PeerConnection` 类 |
| **成员节点零改动** | ✅ 一个节点的代码就能既做单聊又做群聊，只是消息类型不同 |
| **性能瓶颈** | ⚠️ 群越大，群主的转发压力越大。局域网内 20-30 人的群不会有问题 |
| **群主离线** | ❌ 群暂时不可用（消息无处转发）。后续可扩展群主选举 |
| **隐私边界** | ⚠️ 群主能看到所有群消息（因为都经他转发）。接受这个 trade-off，否则需要 P2P 组播，复杂度飙升 |

**结论**：对"局域网小群聊"这个场景，群主转发方案是性价比最高的选择。

### 10.3 群主职责

群主节点在运行时额外承担以下职责：

```java
// 群主内部维护的运行时状态
class GroupLeaderState {
    String groupId;                          // 群 ID
    String groupName;                         // 群名称
    String myDeviceId;                        // 群主自身 deviceId
    Map<String, PeerConnection> members;      // deviceId → 该成员的 TCP 连接
                                             // （包含群主自己，用特殊标记）
}
```

| 职责 | 说明 |
|------|------|
| **成员管理** | 维护在线成员列表（`Map<deviceId, PeerConnection>`） |
| **消息转发** | 收到任何 `GROUP_MESSAGE` → 遍历 members 转发 |
| **入群审批** | 处理 `GROUP_INVITE` 邀请或 `GROUP_JOIN_REQUEST` 申请 |
| **成员通知** | 有人离群/上线/离线时，广播 `GROUP_MEMBER_UPDATE` |
| **元数据同步** | 群名称变更、群主转让等信息广播 |

### 10.4 加入群聊流程

#### 方式一：群主邀请（主动发起）

```
群主 S 操作：选择好友 C1、C2、C3 → 创建群 → 点 "邀请加入"
       │
       ▼
群主 S ── GROUP_INVITE ──▶ C1 ── GROUP_INVITE ──▶ C2 ── ...
       │                                                    │
       │                                          弹窗：群主邀请你加入"项目群"，是否接受？
       │                                                    │
       │                                          C2 点击接受
       │                                                    │
       ▼                                          ▼
群主 S ◀──── GROUP_INVITE_ACCEPT ◀── C2 (同理 C1/C3)
       │
       ▼
群主 S 更新成员列表 → 广播 GROUP_MEMBER_UPDATE 给所有已加入成员
       │
       ▼
所有成员本地更新群成员列表 → 可开始群聊
```

#### 方式二：申请加入（成员主动）

```
成员 C4 知道群 ID → 在界面输入群 ID + 群主 IP:Port → 申请加入
       │
       ▼
C4 ── TCP 连接群主 ──▶ S
       │
       ▼
C4 ── GROUP_JOIN_REQUEST ──▶ S
       │
       ▼
S 收到 → 弹窗："C4 (deviceId: ab12cd34) 申请加入群"
       │
       ▼
S 点击接受 ──▶ 广播 GROUP_MEMBER_UPDATE（包含新成员）
       │
       ▼
所有现有成员 + C4 本地更新成员列表
```

### 10.5 离群与群主离线

#### 成员主动离群
```
C3 → GROUP_LEAVE → S
       S 从成员列表移除 C3
       S 广播 GROUP_MEMBER_UPDATE（不含 C3）给剩余成员
       S 主动断开和 C3 的 TCP 连接
```

#### 成员异常离线（断网/关机）
```
S 的心跳检测发现 C3 超时 → 判定离线
       S 从在线成员列表移除 C3（但保留在"群历史成员"里）
       S 广播 GROUP_MEMBER_UPDATE（标记 C3 为 offline）
       其他成员界面上 C3 变灰/标记离线
```

#### 群主离线（关键边界）

**规则已确认**：群主离线后，**剩余在线成员中一致随机选一个接任新群主**，不需要投票。**群聊结构永远保留，不降级为单聊**——哪怕只剩 2 人也保持群聊模式。

> **为什么不降级单聊？** 降级会导致群历史割裂、群信息丢失、群主回来后无法无缝恢复。保持群聊结构，只是内部选了新群主，UI 和数据层全部连贯。

```
群主 S 离线（心跳超时）
       │
       ▼
所有成员检测到与 S 的连接断开
       │
       ▼
每个剩余在线成员本地执行：
  1. **取上次收到的 GROUP_MEMBER_UPDATE 快照**（而不是"我自己看到的在线列表"！）
     → 这个快照所有成员一致，不存在视角差异
     → 过滤掉旧群主 deviceId（他离线了）
     → 过滤掉自己心跳也已经超时的成员（虽然快照里有，但我已经收不到他的心跳了）
     → 得到 finalOnlineIds（所有人算出来一样）
  2. 如果 finalOnlineIds.size() >= 1（**不管剩几个人都不自动解散**）
     → **一致随机选一个成员当新群主**：
     - 如果剩 1 人 → 随机选的结果就是自己，自动成为群主，群标记 INACTIVE（暂时没人可转发，等别人上线自动恢复）
     - 如果剩 2+ 人 → 正常一致随机选新群主，群聊无缝继续
     - 随机策略：对 finalOnlineIds 排序拼接 + 快照时间戳（**秒粒度**）+ 群Id → hash → Random seed → shuffle → 取第一个
     - 被选中的节点自动承担群主职责：
       · 开启转发逻辑（2 人时群主转发相当于本地 loopback，但统一了代码路径）
       · 主动连接其他成员（已有连接则复用）
       · 广播 GROUP_MEMBER_UPDATE（宣告自己是新群主）
     - 其他成员切换连接目标到新群主
     - UI 显示系统消息："原群主 XXX 已离线，YYY 自动接任群主"
```

**实现细节 —— "各节点独立选但结果一致"（双重修正）**：

**修正 1：用 GROUP_MEMBER_UPDATE 快照，不用"我看到的在线列表"**
```java
// 群主离线前最后一次广播的成员列表快照
// 所有节点都收到了这个广播，所以成员列表一致
// 本地存储：groupRuntimeState.lastMemberSnapshot
Set<String> lastKnownMemberIds = groupRuntimeState.lastMemberSnapshot
    .stream()
    .filter(id -> !id.equals(oldLeaderDeviceId))  // 排除离线群主
    .collect(Collectors.toSet());

// 再过滤掉那些"上次心跳也超时了"的成员（双重保险）
long nowMs = System.currentTimeMillis();
Set<String> finalOnlineIds = lastKnownMemberIds.stream()
    .filter(id -> nowMs - groupRuntimeState.lastHeartbeatOf(id) < 90_000L)
    .collect(Collectors.toSet());
```

**修正 2：随机种子用秒粒度 + 快照时间戳，避免跨分钟不一致**
```java
// seed 的 4 个组成部分，确保所有节点算出完全相同的值
// ① 排序后的成员列表字符串
// ② 群主离线前最后一次 GROUP_MEMBER_UPDATE 的广播时间（秒粒度）
// ③ 群主自己的 deviceId（加入 seed 防止同一秒两个群同时选举撞 seed）
// ④ 群的 groupId

String sortedIds = finalOnlineIds.stream().sorted().collect(Collectors.joining(","));
long snapshotSec = groupRuntimeState.lastMemberSnapshotSec;  // 群主广播时的 timestamp / 1000
String seedInput = sortedIds + ":" + snapshotSec + ":" + oldLeaderDeviceId + ":" + groupId;
long electionSeed = MurmurHash3.hash64(seedInput);

// 用这个 seed 做随机，所有节点结果一致
List<String> shuffled = new ArrayList<>(finalOnlineIds);
Collections.shuffle(shuffled, new Random(electionSeed));

// shuffle 后第一个就是新群主
String newLeaderId = shuffled.get(0);
boolean amINewLeader = myDeviceId.equals(newLeaderId);
```

**为什么这样就不会分裂？**

| 之前的问题 | 修正后 |
|-----------|--------|
| 各节点"自己看到的在线列表"不同 → 选不同群主 | 统一用**群主离线前广播的快照** → 所有节点的成员列表完全一致 |
| 分钟粒度 hash seed → 跨分钟换 seed → 选不同群主 | **秒粒度快照时间戳** → 同一秒内所有节点执行 hash → seed 一致 |
| 没考虑群主 deviceId 撞 hash | seed 里**加了旧群主 deviceId + groupId** → 不同群/不同群主不会撞 seed |

| 场景 | 行为 |
|------|------|
| 群主离线 + 剩余 1 人 | **自动成为新群主**（随机选的结果就是自己），群标记 INACTIVE（暂时没人可转发，等别人上线自动恢复）。**群永不自动解散**，用户手动删除才算 |
| 群主离线 + 剩余 2 人 | 一致随机选新群主，保持群聊模式，不降级。群历史、群名、群成员全部保留 |
| 群主离线 + 剩余 3+ 人 | 一致随机选新群主，自动切换，群聊无缝继续 |
| 新群主也离线了 | 重复上述流程，再一致随机选；剩 1 人自己当群主（INACTIVE），剩 2+ 继续 |
| **任何成员离线后重连**（包括群主、旧群主、普通成员） | 自动连接**当前群主**，恢复在线状态。**已收到的聊天记录因存在本地 SQLite 里天然完整保留**。离线期间群里发的消息不补发（P2P 无服务器存储），但群名、群成员列表、历史消息全部在 |
| 原群主重新上线 | 作为普通成员加入新群主的群，本地历史消息完整。如需归还群主身份由新群主手动转让（Phase 5 做） |
| 手动删除群聊 | 用户在 UI 上点"解散群聊"→ 发 GROUP_DISBAND 广播 → 所有成员本地删除群记录 + 标记 conversations 关闭 |

### 10.6 消息 ACK 机制

群聊的 ACK 和单聊不同——发送方只关心"群主收到了没有"，不关心其他成员：

```
C1 发送 GROUP_MESSAGE
       │
       ▼
       发给群主 S
       │
       ▼
       S 立即回复 ACK (messageId) 给 C1
       │
       ▼
       C1 本地消息状态 → "已送达"（不会被慢成员阻塞）
       │
       ▼
       S 异步遍历 members 转发（即使某个成员暂时慢，也不影响 C1 的体验）
```

| 场景 | 处理 |
|------|------|
| 群主收到但转发时某成员离线 | 跳过离线成员，正常转发给在线的 |
| 群主自己发送消息 | 直接本地 ACK + 遍历转发，不经过网络 |

### 10.7 群文件传输策略

**已确认：群主存完整文件 + 边收边转发**。两个目标同时满足：
- **完整性保证**：群主存完整文件到磁盘 → 成员晚到/掉线回来时，群主可以重新转发给他
- **效率优先**：边收边转发 → 成员尽快开始接收，不用等群主全部收完

```
C1 发送文件到群（100MB report.pdf）
       │
       ▼
C1 → GROUP_FILE_META → 群主 S  ✅ S 立即 ACK 给 C1（让 C1 知道已送达）
       │
       ▼
C1 → GROUP_FILE_CHUNK(0) → S
       │                        │
       │                        ▼
       │                        S 同时做两件事：
       │                        ├── 写 chunk(0) 到本地临时文件（存完整文件）
       │                        └── 遍历在线成员，立即转发 chunk(0)
       │
       ▼  (重复 1599 次，每次约 64KB)
C1 → GROUP_FILE_CHUNK(1599) → S → 存 + 转发
       │
       ▼
C1 → GROUP_FILE_DONE → S → 校验 sha256 → 完整文件移到正式位置 → 广播 GROUP_FILE_DONE
       │
       ▼
   S 转发给所有在线成员：GROUP_FILE_DONE + sha256
```

#### 成员晚到场景（完整性保证生效）

```
C1 发文件给群 → 群主 S 存了完整文件 → 成员 C2 掉线了
       │
       ▼
C2 重新上线 → 连接到群主 S
       │
       ▼
C2 发现自己本地没有这条 GROUP_FILE_DONE → 向群主请求补发
       │
       ▼
群主 S 从磁盘读取完整文件 → 重新分块转发给 C2
（因为群主存了完整文件，所以可以补发）
```

#### 群主自己发文件怎么办？

```
群主 S 自己要发文件到群
       │
       ▼
S 生成本地完整文件（从源路径移动到群文件目录）
       │
       ▼
S 本地 ACK（不走网络）+ 遍历所有在线成员分块转发
（和 C1 发文件的区别：S 不需要"接收"那一步，直接从本地磁盘读块转发）
```

#### 关键实现细节

```java
// 群主收到 GROUP_FILE_CHUNK 时
void onGroupFileChunk(Sender sender, int chunkIndex, byte[] chunkData) {
    // ① 写本地（存完整文件，保证晚到成员能补发）
    Path tempFile = Path.of(fileStore.tempDir, transferId + ".part");
    try (FileChannel fc = FileChannel.open(tempFile, StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
        fc.position((long) chunkIndex * chunkSize);
        fc.write(ByteBuffer.wrap(chunkData));
    }

    // ② 立即转发给所有在线成员（边收边发，效率优先）
    for (Contact member : onlineMembers) {
        if (member.deviceId.equals(sender.deviceId)) continue;  // 不转发回发送方
        sendGroupFileChunk(member, transferId, chunkIndex, chunkData);
    }
}

// 收到 GROUP_FILE_DONE 时
void onGroupFileDone(String transferId, String sha256) {
    Path tempFile = Path.of(fileStore.tempDir, transferId + ".part");
    String actualHash = sha256(tempFile);
    if (!actualHash.equals(sha256)) {
        // hash 不对 → 删除临时文件，标记传输失败
        tempFile.toFile().delete();
        broadcastGroupFileFailed(transferId);
        return;
    }
    // 完整性 OK → 移到群文件正式目录
    Path finalDir = Path.of(fileStore.groupFilesDir, groupId);
    Files.move(tempFile, finalDir.resolve(fileName));
    // 广播文件完成
    broadcastGroupFileDone(transferId, fileName, finalPath.toString());
}
```

#### 群主磁盘占用注意

群主存完整文件 → 如果群里有多个大文件同时传输，群主磁盘会紧张。

| 策略 | 说明 |
|------|------|
| **存临时 + 完后移动** | 传输中用 `.part` 临时文件，完成后才移到正式位置；失败直接删 `.part` |
| **磁盘阈值保护** | 群主检测磁盘剩余空间 < 100MB 时，拒绝新的群文件传输并提示"群主磁盘空间不足" |
| **临时文件自动清理** | 应用启动时扫描 `~/.nexuschat/temp/`，删掉超过 24 小时没完成的 `.part` 文件 |

### 10.8 群聊与单聊的代码复用

```
┌─────────────────────────────────────────────────┐
│              统一的 PeerConnection               │
│  (既能做单聊的连接，也能做群聊成员到群主的连接)    │
└─────────────────────────────────────────────────┘
         │
         │ 根据消息类型路由
         │
    ┌────┴─────┐
    ▼          ▼
  TEXT      GROUP_MESSAGE
  (单聊)      (群聊)
    │          │
    │          ▼
    │    ChatService.routeGroupMessage()
    │          │
    │          ├── 如果我是群主 → 遍历转发给所有成员
    │          └── 如果我是成员 → 直接渲染到群聊界面
    │
    ▼
  ChatService.routePrivateMessage()
    └── 渲染到单聊界面
```

**关键类只需做一个开关**：
```java
class ChatService {
    // 判断当前节点在某个群里的角色
    boolean isGroupLeader(String groupId) {
        return groupLeaderStates.containsKey(groupId);
    }

    // 群主转发逻辑（仅群主执行）
    void forwardAsGroupLeader(String groupId, MessageFrame frame) {
        GroupLeaderState state = groupLeaderStates.get(groupId);
        for (PeerConnection member : state.members.values()) {
            member.send(frame);
        }
    }
}
```

### 10.9 群聊协议消息类型（补全）

在原有 `GROUP_*` 基础上增加群聊专用的消息类型：

```java
public enum MessageType {
    // ... 单聊的类型保持不变 ...

    // ── 群聊：管理类 ──
    GROUP_CREATE(0x0040),          // 群主创建群（本地生成，不发网络）
    GROUP_INVITE(0x0041),          // 群主邀请某人入群
    GROUP_INVITE_ACCEPT(0x0042),   // 被邀请者接受
    GROUP_INVITE_REJECT(0x0043),   // 被邀请者拒绝
    GROUP_JOIN_REQUEST(0x0044),    // 主动申请加入
    GROUP_JOIN_ACCEPT(0x0045),     // 群主同意
    GROUP_JOIN_REJECT(0x0046),     // 群主拒绝
    GROUP_LEAVE(0x0047),           // 成员主动离群
    GROUP_MEMBER_UPDATE(0x0048),   // 成员列表变更广播
    GROUP_DISBAND(0x0049),         // 群主解散群

    // ── 群聊：消息类 ──
    GROUP_MESSAGE(0x0050),         // 群文字/图片消息
    GROUP_FILE_META(0x0051),      // 群文件元数据
    GROUP_FILE_CHUNK(0x0052),     // 群文件数据分片
    GROUP_FILE_ACK(0x0053),       // 群文件分片确认
    GROUP_FILE_DONE(0x0054)       // 群文件传输完成
}
```

### 10.10 群聊本地存储补充

完整群聊表设计见 [DATABASE.md](DATABASE.md) §3.6-3.8（groups / group_members / group_leader_ledger 三张表联动），关键点：

- `groups.my_role` —— 我在这个群里的角色（LEADER / MEMBER），本地判断用
- `groups.current_leader_id` —— 冗余字段，快速查当前群主，每次选举时同步更新
- `group_members.is_leader` —— 和 groups.current_leader_id 双向冗余
- `group_leader_ledger` —— **群主自动选举的关键表**，每次群主变更记录一条（reason=AUTO_ELECTION），用于追溯和一致性验证

**会话 ID**：群聊的 conversation_id 存在 `conversations` 表（type='GROUP' → group_id → groups.id），不走拼接字符串。

### 10.11 群聊 Phase 范围界定

| 子功能 | Phase | 说明 |
|--------|-------|------|
| 创建群 + 邀请好友 | 3 | MVP |
| 群主转发消息 | 3 | MVP |
| 成员列表显示 | 3 | MVP |
| 群内文件/图片传输 | 3 | 复用单聊的文件逻辑 |
| 主动申请加入群 | 3 | 可选 |
| 群主离线 → 一致随机选新群主 | 3 | **已确认方案**，不管剩 1/2/3+ 人都走这个逻辑 |
| 群聊自动解散 | ❌ | **已定案排除**，群永不自动解散，只允许用户手动删除 |
| 群聊降级单聊 | ❌ | **已定案排除**，群聊结构永远保留 |
| 群主转让（手动） | 5 | 后续增强 |

---

## 11. 配置项

### 11.1 AppConfig（可写入 `~/.nexuschat/config.properties`）

| Key | 默认值 | 说明 |
|-----|--------|------|
| `node.listen.port` | 15000 | **本地监听端口**。启动时从配置读；首次无配置时用默认值。设置面板可运行时修改，修改后自动关闭旧 ServerSocket、绑定新端口、写回配置。UI 状态栏必须**明显展示**当前监听端口 |
| `node.nickname` | "NexusChat User" | 显示昵称。设置面板可修改 |
| `node.data.dir` | `~/.nexuschat` | 数据目录 |
| `network.heartbeat.interval` | 30000 | 心跳间隔（毫秒） |
| `network.heartbeat.timeout` | 90000 | 心跳超时（毫秒） |
| `network.file.chunk.size` | 65536 | 文件分块大小（字节） |
| `network.file.connect.timeout` | 5000 | 连接超时（毫秒） |
| `ui.theme` | "dark" | 界面主题 |

### 11.2 监听端口 UI 展示要求

| 位置 | 格式 | 说明 |
|------|------|------|
| **主窗口顶部/状态栏** | `🔌 监听端口: 15000` | 常驻显示，用户一开就看到。不能藏在菜单里 |
| **设置面板** | 输入框 + "应用"按钮 | 支持手动改端口，输入后点应用触发 `P2PNode.changeListenPort()` |
| **添加好友对话框** | 自动填充本机端口作为默认值 | 方便用户对照自己界面上显示的端口告诉对方 |
| **修改成功/失败提示** | Alert 弹窗 | 成功："监听端口已改为 15001"；失败："端口 15001 已被占用" |

---

## 12. 安全与隐私

| 关注点 | 处理方式 |
|--------|---------|
| **身份识别** | 启动时生成 8 字符 deviceId 指纹，写入本地，不可修改 |
| **连接鉴权** | 握手阶段交换 HELLO，对端 deviceId 必须在本地 contacts 表中标记为"已接受"才能继续通信 |
| **通信保密** | **已确认：局域网内明文传输**。同网段可信，不做 TLS。后续如有跨网段/公网需求再评估 |
| **好友验证** | 双向确认制：A 发请求 → B 接受 → 双方互相加好友 |
| **本地数据** | SQLite 文件 + images/files 目录，整个 `~/.nexuschat` 目录权限设为 700 |

---

## 13. 分阶段里程碑

> 详细功能清单和优先级见 [DESIGN_GOALS.md](DESIGN_GOALS.md)

### Phase 1 — 骨架 + 单聊 MVP 🎯 先做这个
- [ ] 项目骨架搭建（Maven + JavaFX + Java 25）
- [ ] P2PNode：ServerSocket 监听 + Socket 主动连接
- [ ] FrameCodec：帧编解码 + 粘包处理
- [ ] HELLO / HELLO_ACK 握手
- [ ] HEARTBEAT 心跳保活
- [ ] SQLite 初始化（9 张表：app_config / contacts / contact_groups / contact_group_membership / conversations / messages / file_transfers / groups / group_members / group_leader_ledger）
- [ ] 主窗口 UI：联系人列表 + 聊天区域 + 输入框
- [ ] 添加好友流程（手动 IP:Port）
- [ ] TEXT 消息收发 + 本地存储

**交付标准**：两台机器能互相添加好友、收发文字消息、断线后能重连。

### Phase 2 — 文件与图片
- [ ] FileManager + FILE_META/FILE_CHUNK/FILE_ACK/FILE_DONE 完整链路
- [ ] 图片压缩 + 缩略图
- [ ] 文件进度条 UI + 取消传输
- [ ] 文件/图片消息在聊天界面的渲染
- [ ] messages 表 media_path 关联显示

### Phase 3 — 群聊核心（设计目标已包含，不延后）
- [ ] GROUP_CREATE / GROUP_INVITE / GROUP_INVITE_ACCEPT / GROUP_LEAVE / GROUP_MEMBER_UPDATE
- [ ] 群主协调转发逻辑（`ChatService.isGroupLeader()` + `forwardAsGroupLeader()`）
- [ ] 群主做群消息 ACK（发送方只等群主 ACK，不被慢成员阻塞）
- [ ] 群内文字/图片/文件（复用 Phase 2 的传输逻辑，群主做中转）
- [ ] 群聊 UI：成员列表 + 群消息样式 + 群主标记
- [ ] 群主离线 → **一致随机选新群主**（剩 1 人自己当群主 + 群 INACTIVE）
- [ ] 群聊永不自动解散，只允许用户手动删除
- [ ] groups / group_members 表 + messages.conversationId = "group-xxx"

**交付标准**：3 人能建群、互相邀请、在群里发文字/图片/文件；其中一人离线后剩下人自动选新群主继续聊；群聊结构、历史、群名全程保留，不会降级为单聊。

### Phase 4 — 稳健性增强
- [ ] 自动重连机制（断线指数退避重连）
- [ ] 连接状态 UI 指示（在线/离线/连接中）
- [ ] 消息状态流转（已发送/已送达/失败重发）
- [ ] 历史消息分页加载（聊天界面切换会话时加载最近 N 条）
- [ ] 多网卡环境 IP 选择（NetworkUtil 枚举 + 配置项）

### Phase 5（后续增强）
- [ ] 群主手动转让
- [ ] 消息搜索（跨会话）
- [ ] 消息引用/回复
- [ ] 系统托盘 + 消息通知

---

## 14. 风险与注意事项

| 风险 | 影响 | 对策 |
|------|------|------|
| JavaFX + 虚拟线程兼容性 | 低风险 | 虚拟线程只跑在后台，UI 线程仍是 Platform thread，互不干扰 |
| 多网卡环境绑错 IP | 连接失败 | 启动时枚举所有网卡 IP，让用户选择绑定哪个；配置持久化 |
| NAT 穿透（跨网段） | 当前版本不支持 | 明确范围：只适用于同一局域网（路由器内），不做广域网穿透 |
| SQLite 文件损坏 | 数据丢失 | 定期 WAL checkpoint；重要写入包裹事务；应用启动时做完整性检查 |
| 文件传输中断 | 接收方收到半成品 | FILE_DONE 必须校验 sha256，不通过则删除临时文件；支持续传预留 |

---

## 附录 A：FrameCodec 粘包伪代码

```java
class FrameCodec {
    // 从 InputStream 中完整读取一帧
    MessageFrame readFrame(InputStream in) throws IOException {
        // 1. 读 2B Magic
        byte[] magic = readExact(in, 2);
        if (magic[0] != 0xCA || magic[1] != 0xFE) {
            throw new IOException("Invalid magic");
        }

        // 2. 读 2B Type
        byte[] typeBytes = readExact(in, 2);
        MessageType type = MessageType.fromCode(
            ((typeBytes[0] & 0xFF) << 8) | (typeBytes[1] & 0xFF)
        );

        // 3. 读 4B Length (大端)
        byte[] lenBytes = readExact(in, 4);
        int length = ByteBuffer.wrap(lenBytes).getInt();

        // 4. 读 Payload
        byte[] payload = readExact(in, length);

        return new MessageFrame(type, payload);
    }

    // 必须精准读满 N 字节，TCP 可能分多次返回
    byte[] readExact(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int read = 0;
        while (read < n) {
            int r = in.read(buf, read, n - read);
            if (r == -1) throw new IOException("Stream closed");
            read += r;
        }
        return buf;
    }

    // 将帧写入 OutputStream
    void writeFrame(OutputStream out, MessageFrame frame) throws IOException {
        byte[] payload = frame.getPayload();
        out.write(new byte[]{0xCA, 0xFE});                          // Magic
        out.write(new byte[]{frame.getType().highByte(),              // Type
                             frame.getType().lowByte()});
        out.write(ByteBuffer.allocate(4).putInt(payload.length).array()); // Length
        out.write(payload);                                          // Payload
        out.flush();
    }
}
```

## 附录 B：deviceId 生成策略

```java
class DeviceIdGenerator {
    // 首次启动时：基于 MAC 地址 + UUID 哈希，生成 8 字符十六进制
    // 写入 ~/.nexuschat/device_id 文件，终身不变
    String generateOrLoad() {
        File file = new File(dataDir, "device_id");
        if (file.exists()) {
            return Files.readString(file.toPath()).trim();
        }
        String raw = MACAddressUtil.getPrimaryMac() + "-" + UUID.randomUUID();
        String id = DigestUtils.sha256Hex(raw).substring(0, 8).toUpperCase();
        Files.writeString(file.toPath(), id);
        return id;
    }
}
```

## 附录 C：对话 ID 生成

```java
// 双方 deviceId 排序后拼接，保证会话 ID 一致
String conversationId = Stream.of(myDeviceId, peerDeviceId)
    .sorted()
    .collect(Collectors.joining("-"));
```
