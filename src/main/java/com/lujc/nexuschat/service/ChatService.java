package com.lujc.nexuschat.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lujc.nexuschat.enums.HelloRejectCode;
import com.lujc.nexuschat.enums.MessageDirection;
import com.lujc.nexuschat.enums.MessageStatus;
import com.lujc.nexuschat.enums.MessageType;
import com.lujc.nexuschat.network.P2PNode;
import com.lujc.nexuschat.network.PeerConnection;
import com.lujc.nexuschat.protocol.Frame;
import com.lujc.nexuschat.storage.*;
import com.lujc.nexuschat.util.DeviceIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 聊天业务协调服务 —— Phase 1 核心业务层。
 * <p>
 * 协调：P2PNode（网络） + DatabaseManager（存储） + Repositories（数据访问）。
 * UI 层通过回调 {@link #setOnIncomingText} / {@link #setOnStatusUpdate} 接收事件。
 * <p>
 * 数据库连接由 DatabaseManager 的 ThreadLocal 统一管理，
 * ChatService 只从 getConnection() 取，不 close（由 ThreadLocal 管生命周期）。
 */
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final Gson GSON = new Gson();

    /** 应用与协议版本，升级时与 pom.xml / 打包脚本 APP_VERSION 同步修改。 */
    public static final String APP_VERSION = "1.5.0";

    private final P2PNode node;
    private final String deviceId;
    private String nickname;
    private int listenPort;

    private final FileTransferService fileTransferService;

    private Consumer<IncomingText> onIncomingText;
    private Consumer<String> onStatusUpdate;
    private Consumer<FileTransferService.FileEvent> onFileEvent;

    private enum State { DISCONNECTED, CONNECTING, CONNECTED }
    private State state = State.DISCONNECTED;

    public ChatService() throws SQLException {
        this.deviceId = DeviceIdGenerator.loadOrGenerate();

        DatabaseManager.initSchema();

        Connection conn = DatabaseManager.getConnection();
        AppConfigRepository cfgRepo = new AppConfigRepository(conn);
        this.listenPort = cfgRepo.getInt("node.listen.port", 15000);
        this.nickname = cfgRepo.get("node.nickname").orElse("NexusChat User");

        this.node = new P2PNode(deviceId, nickname);
        this.fileTransferService = new FileTransferService(deviceId, nickname,
            ev -> {
                Consumer<FileTransferService.FileEvent> h = this.onFileEvent;
                if (h != null) h.accept(ev);
            });
        registerFrameHandler();
    }

    public void start() throws Exception {
        node.startListening(listenPort);
        log.info("ChatService 启动: deviceId={}, nickname={}, port={}", deviceId, nickname, listenPort);
        notifyStatus("监听端口 " + listenPort + " | deviceId=" + deviceId + " | " + nickname);
    }

    public void stop() {
        node.stop();
        notifyStatus("已停止");
    }

    public void setOnIncomingText(Consumer<IncomingText> handler) {
        this.onIncomingText = handler;
    }

    public void setOnStatusUpdate(Consumer<String> handler) {
        this.onStatusUpdate = handler;
    }

    public void setOnFileEvent(Consumer<FileTransferService.FileEvent> handler) {
        this.onFileEvent = handler;
    }

    /** 向指定好友发送文件/图片（内部按 64KB 分片 + 逐块 ACK）。 */
    public void sendFile(String peerDeviceId, java.nio.file.Path file) {
        PeerConnection conn = node.getConnection(peerDeviceId);
        if (conn == null || conn.isClosed()) {
            notifyStatus("对方不在线: " + peerDeviceId);
            return;
        }
        fileTransferService.sendFile(conn, peerDeviceId, file);
    }

    /** 主动添加好友：连接对方 IP:Port → 发 HELLO → 处理 HELLO_ACK。 */
    public void addFriend(String ip, int port) {
        virtualThread(() -> {
            try {
                notifyStatus("连接 " + ip + ":" + port + "...");
                state = State.CONNECTING;

                PeerConnection conn = node.connectTo(ip, port);

                JsonObject hello = new JsonObject();
                hello.addProperty("nickname", this.nickname);
                hello.addProperty("listenPort", this.listenPort);
                hello.addProperty("version", APP_VERSION);
                hello.addProperty("deviceId", this.deviceId);
                hello.addProperty("timestamp", System.currentTimeMillis());
                conn.send(Frame.of(MessageType.HELLO, GSON.toJson(hello)));

                notifyStatus("已连接 " + ip + ":" + port);
                state = State.CONNECTED;

            } catch (Exception e) {
                log.warn("连接失败 {}:{} — {}", ip, port, e.getMessage());
                notifyStatus("连接失败 " + ip + ":" + port + " — " + e.getMessage());
                state = State.DISCONNECTED;
            }
        });
    }

    public void sendText(String peerDeviceId, String content) {
        virtualThread(() -> {
            try {
                PeerConnection conn = node.getConnection(peerDeviceId);
                if (conn == null || conn.isClosed()) {
                    notifyStatus("对方不在线: " + peerDeviceId);
                    return;
                }

                String msgId = UUID.randomUUID().toString();
                long ts = System.currentTimeMillis();

                JsonObject text = new JsonObject();
                text.addProperty("msgId", msgId);
                text.addProperty("content", content);
                text.addProperty("timestamp", ts);
                text.addProperty("senderDeviceId", this.deviceId);

                conn.send(Frame.of(MessageType.TEXT, GSON.toJson(text)));
                log.info("TEXT 已发送 → {}, msgId={}", peerDeviceId, msgId);

                saveLocalMessage(msgId, peerDeviceId, content, MessageDirection.SENT, MessageStatus.DELIVERED);

            } catch (Exception e) {
                log.error("发送 TEXT 失败", e);
                notifyStatus("发送失败: " + e.getMessage());
            }
        });
    }

    // ── 内部：帧处理分发 ──

    private void registerFrameHandler() {
        node.setOnFrame((conn, frame) -> {
            switch (frame.type()) {
                case HELLO -> handleHello(conn, frame);
                case HELLO_ACK -> handleHelloAck(conn, frame);
                case TEXT -> handleText(conn, frame);
                case ACK -> handleAck(frame);
                case BYE -> handleBye(conn);
                case FILE_META, FILE_CHUNK, FILE_ACK, FILE_DONE, CANCEL_TRANSFER ->
                    fileTransferService.handleFrame(conn, frame);
                default -> log.debug("暂未处理的消息类型: {}", frame.type());
            }
        });

        node.setOnDisconnect((conn, reason) -> {
            log.info("{} 断开: {}", conn, reason);
            fileTransferService.onConnectionClosed(conn);
            virtualThread(() -> {
                try {
                    new ContactRepository(DatabaseManager.getConnection())
                        .updateOnline(conn.peerDeviceId(), false);
                } catch (SQLException ignored) {}
            });
            notifyStatus(conn + " 已断开");
        });

        node.setOnConnectionAccepted(conn -> log.info("入站连接: {}", conn));
    }

    private void handleHello(PeerConnection conn, Frame frame) {
        try {
            JsonObject payload = JsonParser.parseString(frame.payloadAsString()).getAsJsonObject();
            String peerDeviceId = payload.get("deviceId").getAsString();
            String peerNickname = payload.get("nickname").getAsString();
            int peerListenPort = payload.get("listenPort").getAsInt();
            String peerIp = conn.peerIp();

            // 版本强校验：不兼容直接拒绝，不落库、不建会话
            String peerVersion = optString(payload, "version");
            if (!versionCompatible(peerVersion)) {
                rejectByVersion(conn, peerVersion);
                return;
            }

            conn.setPeerInfo(peerDeviceId, peerNickname);

            log.info("收到 HELLO: {} ({}) version={} from {}", peerNickname, peerDeviceId, peerVersion, peerIp);

            Connection dbConn = DatabaseManager.getConnection();
            new ContactRepository(dbConn).insertOrUpdate(peerDeviceId, peerNickname, peerIp, peerListenPort);
            new ContactRepository(dbConn).updateOnline(peerDeviceId, true);

            String contactId = new ContactRepository(dbConn)
                .findByDeviceId(peerDeviceId).map(c -> c.id()).orElse(null);
            if (contactId != null) {
                new ConversationRepository(dbConn).createPrivate(deviceId, peerDeviceId, contactId);
            }

            JsonObject ack = new JsonObject();
            ack.addProperty("status", "ACCEPTED");
            ack.addProperty("nickname", this.nickname);
            ack.addProperty("deviceId", this.deviceId);
            ack.addProperty("version", APP_VERSION);
            ack.addProperty("timestamp", System.currentTimeMillis());
            conn.send(Frame.of(MessageType.HELLO_ACK, GSON.toJson(ack)));

            notifyStatus("好友 " + peerNickname + " (" + peerDeviceId + ") 已连接");

        } catch (Exception e) {
            log.error("HELLO 处理异常", e);
        }
    }

    private void handleHelloAck(PeerConnection conn, Frame frame) {
        try {
            JsonObject payload = JsonParser.parseString(frame.payloadAsString()).getAsJsonObject();
            String status = payload.get("status").getAsString();

            if ("ACCEPTED".equals(status)) {
                // 主动发起方同样校验对端版本（老版本 HELLO_ACK 不含 version 字段，视为不兼容）
                String peerVersion = optString(payload, "version");
                if (!versionCompatible(peerVersion)) {
                    String reason = "版本不兼容：本机 " + APP_VERSION
                        + "，对方 " + (peerVersion == null ? "未知" : peerVersion);
                    log.warn("拒绝 {} — {}", conn, reason);
                    notifyStatus(reason);
                    conn.close();
                    return;
                }

                String peerDeviceId = payload.get("deviceId").getAsString();
                String peerNickname = payload.get("nickname").getAsString();
                conn.setPeerInfo(peerDeviceId, peerNickname);
                log.info("HELLO_ACK ACCEPTED: {} ({}) version={}", peerNickname, peerDeviceId, peerVersion);
                notifyStatus(peerNickname + " 接受了连接");

                Connection dbConn = DatabaseManager.getConnection();
                new ContactRepository(dbConn).insertOrUpdate(peerDeviceId, peerNickname, conn.peerIp(), conn.peerPort());
                new ContactRepository(dbConn).updateOnline(peerDeviceId, true);

                // 本机主动发起，也要建私聊会话（file_transfers 有 conversation_id 外键）
                String contactId = new ContactRepository(dbConn)
                    .findByDeviceId(peerDeviceId).map(c -> c.id()).orElse(null);
                if (contactId != null) {
                    new ConversationRepository(dbConn).createPrivate(deviceId, peerDeviceId, contactId);
                }
            } else {
                int rejectCode = payload.has("rejectCode") ? payload.get("rejectCode").getAsInt() : 255;
                HelloRejectCode code = HelloRejectCode.fromCode(rejectCode);
                String reason = payload.has("reason") ? payload.get("reason").getAsString() : code.defaultMessage();
                log.warn("HELLO_ACK REJECTED: code={}, reason={}", rejectCode, reason);
                notifyStatus(reason);
            }
        } catch (Exception e) {
            log.error("HELLO_ACK 处理异常", e);
        }
    }

    private void handleText(PeerConnection conn, Frame frame) {
        try {
            JsonObject payload = JsonParser.parseString(frame.payloadAsString()).getAsJsonObject();
            String msgId = payload.get("msgId").getAsString();
            String content = payload.get("content").getAsString();
            long timestamp = payload.get("timestamp").getAsLong();
            String senderDeviceId = payload.get("senderDeviceId").getAsString();

            log.info("收到 TEXT from {}: {}", senderDeviceId, content);

            JsonObject ack = new JsonObject();
            ack.addProperty("msgId", msgId);
            ack.addProperty("timestamp", System.currentTimeMillis());
            ack.addProperty("status", "OK");
            conn.send(Frame.of(MessageType.ACK, GSON.toJson(ack)));

            saveLocalMessage(msgId, senderDeviceId, content, MessageDirection.RECEIVED, MessageStatus.DELIVERED);

            if (onIncomingText != null) {
                onIncomingText.accept(new IncomingText(senderDeviceId, content, timestamp));
            }

        } catch (Exception e) {
            log.error("TEXT 处理异常", e);
        }
    }

    private void handleAck(Frame frame) {
        try {
            JsonObject payload = JsonParser.parseString(frame.payloadAsString()).getAsJsonObject();
            log.debug("收到 ACK: msgId={}", payload.get("msgId").getAsString());
        } catch (Exception e) {
            log.error("ACK 处理异常", e);
        }
    }

    private void handleBye(PeerConnection conn) {
        log.info("收到 BYE from {}", conn.peerDeviceId());
        conn.close();
    }

    // ── 工具方法 ──

    /** 回送 VERSION_MISMATCH 并断开，不作任何落库。 */
    private void rejectByVersion(PeerConnection conn, String peerVersion) {
        String reason = "版本不兼容：本机 " + APP_VERSION
            + "，对方 " + (peerVersion == null ? "未知" : peerVersion);
        log.warn("拒绝 {} — {}", conn, reason);

        JsonObject ack = new JsonObject();
        ack.addProperty("status", "REJECTED");
        ack.addProperty("rejectCode", HelloRejectCode.VERSION_MISMATCH.code());
        ack.addProperty("reason", reason);
        ack.addProperty("version", APP_VERSION);
        ack.addProperty("deviceId", deviceId);
        ack.addProperty("timestamp", System.currentTimeMillis());
        try {
            conn.send(Frame.of(MessageType.HELLO_ACK, GSON.toJson(ack)));
        } catch (Exception e) {
            log.debug("回送版本拒绝 ACK 失败: {}", e.getMessage());
        }
        conn.close();
        notifyStatus(reason);
    }

    /** 兼容策略：主版本 + 次版本一致即可互通（1.5.x ↔ 1.5.y），缺失或格式非法一律视为不兼容。 */
    private static boolean versionCompatible(String peerVersion) {
        if (peerVersion == null) return false;
        int[] mine = parseMajorMinor(APP_VERSION);
        int[] theirs = parseMajorMinor(peerVersion);
        return mine != null && theirs != null && mine[0] == theirs[0] && mine[1] == theirs[1];
    }

    private static int[] parseMajorMinor(String version) {
        String[] parts = version.trim().split("\\.");
        if (parts.length < 2) return null;
        try {
            return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String optString(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private void saveLocalMessage(String msgId, String peerDeviceId, String content,
                                   MessageDirection direction, MessageStatus status) {
        long now = System.currentTimeMillis();
        try {
            Connection dbConn = DatabaseManager.getConnection();

            String convId = java.util.stream.Stream.of(deviceId, peerDeviceId)
                .sorted().collect(java.util.stream.Collectors.joining("-"));

            String senderNickname;
            if (direction == MessageDirection.SENT) {
                senderNickname = this.nickname;
            } else {
                senderNickname = new ContactRepository(dbConn)
                    .findByDeviceId(peerDeviceId)
                    .map(ContactRepository.Contact::nickname)
                    .orElse(peerDeviceId);
            }

            new MessageRepository(dbConn).insertOrIgnore(new MessageRepository.MessageRow(
                msgId, convId, peerDeviceId, senderNickname,
                "TEXT", content, null, null, null,
                null, now, now, direction, status
            ));

            String preview = content.length() > 50 ? content.substring(0, 50) + "..." : content;
            new ConversationRepository(dbConn).updateLastMessage(convId, preview, "TEXT");

        } catch (SQLException e) {
            log.error("消息入库失败", e);
        }
    }

    private void notifyStatus(String msg) {
        log.info("状态: {}", msg);
        if (onStatusUpdate != null) onStatusUpdate.accept(msg);
    }

    private void virtualThread(Runnable r) {
        Thread.ofVirtual().start(() -> {
            try {
                r.run();
            } finally {
                // 任务结束释放本线程的 SQLite 连接，避免连接数无限增长
                DatabaseManager.releaseCurrent();
            }
        });
    }

    public String deviceId() { return deviceId; }
    public String nickname() { return nickname; }
    public int listenPort() { return listenPort; }
    public P2PNode node() { return node; }

    public record IncomingText(String senderDeviceId, String content, long timestamp) {}
}
