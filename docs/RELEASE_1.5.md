# NexusChat 1.5.0 发行说明

| 项目 | 值 |
| --- | --- |
| 应用版本（Maven / 安装包） | `1.5.0` |
| 协议版本（HELLO.version） | `1.5.0` |
| 数据库 Schema 版本 | `1`（见 `app_config.schema_version`） |
| 发布日期 | 2026-11-06 |
| 运行时 | JDK 25（jlink 精简 JRE）+ JavaFX 25.0.1 |
| 本地数据目录 | `~/.nexuschat/`（`nexuschat.db`、`files/`、`temp/`） |

对应里程碑：Phase 1（P2P 骨架 + 文字聊天）+ Phase 2（文件 / 图片传输）。

## 1. 本版能力

### 1.1 网络与连接
- 完全无中心 P2P：无服务端、无 UDP 广播、无自动发现，仅通过手动输入 `IP:端口` 添加好友。
- 每个连接独立虚拟线程读循环；帧格式 `Magic(2B) + Type(2B) + Length(4B) + Payload`，自带粘包/半包处理。
- 双向同时连接冲突按 `listenPort` 大者保留被动连接（见 ARCHITECTURE §3.6）。
- 监听端口启动时在顶部状态栏与底部状态栏明显展示。
- **握手版本强校验**：主版本 + 次版本必须一致（1.5.x ↔ 1.5.y）。不兼容时回送 `HELLO_ACK(REJECTED, rejectCode=1 VERSION_MISMATCH)` 并立即断开，不写联系人、不建会话。校验在被动方（收到 HELLO）与主动方（收到 HELLO_ACK）双向执行。

### 1.2 文字聊天
- 私聊文字收发，双方各自本地落库（`messages` / `conversations`），会话预览与未读数维护。
- 气泡式消息流：己方靠右、对方靠左，系统提示居中。

### 1.3 文件 / 图片传输（本版重点）
- 协议：`FILE_META` → `FILE_ACK(-1)` → `FILE_CHUNK`（64KB/片，4B 大端序号）→ 逐片 `FILE_ACK` → `FILE_DONE`（sha256）→ `FILE_ACK(-2)`。
- 可靠性：单片 ACK 超时 10s、最多重发 3 次；重复分片幂等（ACK 丢失不会中断传输）；发送端失败主动发 `CANCEL_TRANSFER`；连接断开自动清理未完成入站传输与 `.part` 临时文件。
- 完整性：先写 `temp/*.part`，`sha256` 与大小校验通过后才移动到 `files/<transferId>_<文件名>`，校验失败自动删除并提示重发。
- 上限：单文件 4GB；同一连接同一时刻仅支持一个入站传输（对端会收到明确拒绝原因）。
- UI：底部进度条实时显示百分比与速率；图片直接渲染 320px 缩略图，点击用系统默认程序打开；其他文件显示为可点击文件卡片。
- 落库：`file_transfers` 记录方向/进度/速率/状态；`messages` 按 mime 写入 `IMAGE` 或 `FILE`，会话预览为 `[图片] ` / `[文件] ` 前缀。

### 1.4 UI 改进
- 输入区为多行文本域（`TextArea`）：**Enter 发送，Shift+Enter 换行**。
- 消息区滚动：内容超过可视高度出现竖向滚动条；窗口窄于内容最小宽度（460px）时出现横向滚动条，不再裁切消息。

## 2. 构建与产物

```bash
# macOS（默认产出 .app；PACKAGE_TYPE=dmg 产出 dmg）
bash scripts/build-mac.sh
# 产物：release/NexusChat-1.5.0-mac-<arch>/NexusChat.app

# Linux（PACKAGE_TYPE=deb|rpm）
bash scripts/build-linux.sh

# Windows（PACKAGE_TYPE=msi|zip，或用 build-windows-msi.bat / build-windows-zip.bat）
scripts\build-windows.bat
```

版本号统一在以下位置，升级时需同步修改：`pom.xml`、`NexusChatApp` 窗口标题、`ChatService` 的 `HELLO.version`、三个打包脚本的 `APP_VERSION` 默认值。

开发期运行：`mvn javafx:run` / `mvn exec:java` / `java -jar target/nexuschat-1.5.0.jar`。

## 3. 验证记录

| 验证项 | 方式 | 结果 |
| --- | --- | --- |
| 编译 / 打包 | `mvn clean package -DskipTests` | 通过 |
| 应用启动 | `java -jar` 启动并加载主窗口 | 通过，无异常 |
| 文件传输 | 双进程（独立 `user.home`）传输 1.5MB 随机文件 | 通过，接收端 sha256 与源文件一致 |
| 图片传输 | 传输 `.png` | 通过，落库 `IMAGE` + `image/png` |
| 传输落库 | 查询 `file_transfers` / `messages` / `conversations` | 通过（COMPLETED / 100%、预览正确） |
| 临时文件清理 | 传输后检查 `temp/` | 通过，无残留 |
| 版本兼容握手 | 两个 1.5.0 实例互连 | 通过，双方进入已连接状态 |
| 版本不兼容拒绝 | 冒充 `version=0.1.0` 发起 HELLO | 通过，回 `REJECTED/rejectCode=1`，contacts 表 0 行 |
| 打包产物启动 | 双击 `NexusChat.app` | 通过 |

## 4. 已知限制

- **群聊未实现**（Phase 3）：群主转发、群主离线后一致随机选新群主、永不降级、永不自动解散均尚未落地。
- 明文传输，无 TLS、无消息加密；仅建议在可信局域网内使用。
- 未做自动重连 / 在线心跳维持：对端重启后需重新添加或重连。
- 监听端口暂不支持运行时修改（需改配置后重启）。
- 传输过程无“取消/暂停”入口，只能等失败自动取消。
- 聊天记录未分页，一次性加载全量消息。
- 跨网段（NAT 穿透）未实现。
- Windows / Linux 安装包尚未在真机验证，仅 macOS 实测。

## 5. 数据兼容

- Schema 版本仍为 `1`，本版未新增表，直接沿用 Phase 1 的 10 张表。
- 从 0.1.0 升级：本地 `~/.nexuschat/nexuschat.db` 可直接复用，无需迁移。
- 协议版本为 `1.5.0`，并已启用强校验：只有主版本、次版本一致的客户端才能握手成功；`0.1.0` 等旧版本会被拒绝并收到“版本不兼容”提示。后续调整协议时需同时提升次版本号。
