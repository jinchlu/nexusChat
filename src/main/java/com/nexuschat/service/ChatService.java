package com.nexuschat.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nexuschat.enums.HelloRejectCode;
import com.nexuschat.enums.MessageDirection;
import com.nexuschat.enums.MessageStatus;
import com.nexuschat.enums.MessageType;
import com.nexuschat.network.P2PNode;
import com.nexuschat.network.PeerConnection;
import com.nexuschat.protocol.Frame;
import com.nexuschat.storage.*;
import com.nexuschat.util.DeviceIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 聊天业务协调服务 —— Phase 1 核心业务层。
 * <p>
 * 协调：P2PNode（网络） + DatabaseManager（存储） + Repositories（数据访问）。
 * UI 层通过回调 {@link #setOnIncomingText} / {@link #setOnStatusUpdate} 接收事件。
 */
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final Gson GSON = new Gson();

    // ── 依赖 ──
    private final P2PNode node;
    private final String deviceId;
    private String nickname;
    private int listenPort;

    // ── 回调（UI 层注入）──
    private Consumer<IncomingText> onIncomingText;
    private Consumer<String> onStatusUpdate;

    // ── 内部状态 ──
    private enum State { DISCONNECTED, CONNECTING, CONNECTED }
    private State state = State.DISCONNECTED;

    public ChatService() throws SQLException {
        this.deviceId = DeviceIdGenerator.loadOrGenerate();

        // 先初始化 Schema（幂等）
        DatabaseManager.initSchema();

        // 从配置加载端口和昵称
        try (var conn = DatabaseManager.getConnection()) {
            AppConfigRepository cfgRepo = new AppConfigRepository(conn);
            this.listenPort = cfgRepo.getInt("node.listen.port", 15000);
            this.nickname = cfgRepo.get("node.nickname").orElse("NexusChat User");
        }

        // 初始化网络节点
        this.node = new P2PNode(deviceId, nickname);
        registerFrameHandler();
    }

    // ═══════════════════════════════════════════════════════
    // 生命周期
    // ═══════════════════════════════════════════════════════

    public void start() throws Exception {
        node.startListening(listenPort);
        log.info("ChatService 启动: deviceId={}, nickname={}, port={}", deviceId, nickname, listenPort);
        notifyStatus("✅ 监听端口 " + listenPort + " | deviceId=" + deviceId + " | " + nickname);
    }

    public void stop() {
        node.stop();
        notifyStatus("已停止");
    }

    // ═══════════════════════════════════════════════════════
    // 回调注入（UI 层调用）
    // ═══════════════════════════════════════════════════════

    public void setOnIncomingText(Consumer<IncomingText> handler) {
        this.onIncomingText = handler;
    }

    public void setOnStatusUpdate(Consumer<String> handler) {
        this.onStatusUpdate = handler;
    }

    // ═══════════════════════════════════════════════════════
    // 好友添加（手动 IP:Port）
    // ═══════════════════════════════════════════════════════

    /**
     * 主动添加好友：连接对方 IP:Port → 发 HELLO → 处理 HELLO_ACK。
     * Phase 1 简化版：握手接受后直接写入 contacts，不做 FRIEND_REQUEST 双向确认。
     */
    public void addFriend(String ip, int port) {
        virtualThread(() -> {
            try {
                notifyStatus("🔄 连接 " + ip + ":" + port + "...");
                state = State.CONNECTING;

                PeerConnection conn = node.connectTo(ip, port);

                // 发送 HELLO
                JsonObject hello = new JsonObject();
                hello.addProperty("nickname", this.nickname);
                hello.addProperty("listenPort", this.listenPort);
                hello.addProperty("version", "0.1.0");
                hello.addProperty("deviceId", this.deviceId);
                hello.addProperty("timestamp", System.currentTimeMillis());
                conn.send(Frame.of(MessageType.HELLO, GSON.toJson(hello)));

                notifyStatus("✅ 已连接 " + ip + ":" + port);
                state = State.CONNECTED;

            } catch (Exception e) {
                log.warn("连接失败 {}:{} — {}", ip, port, e.getMessage());
                notifyStatus("❌ 连接失败 " + ip + ":" + port + " — " + e.getMessage());
                state = State.DISCONNECTED;
            }
        });
    }

    // ═══════════════════════════════════════════════════════
    // 发送 TEXT 消息
    // ═══════════════════════════════════════════════════════

    public void sendText(String peerDeviceId, String content) {
        virtualThread(() -> {
            try {
                PeerConnection conn = node.getConnection(peerDeviceId);
                if (conn == null || conn.isClosed()) {
                    notifyStatus("❌ 对方不在线: " + peerDeviceId);
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

                // 本地也存一份（direction = SENT）
                saveLocalMessage(msgId, peerDeviceId, content, MessageDirection.SENT, MessageStatus.DELIVERED);

            } catch (Exception e) {
                log.error("发送 TEXT 失败", e);
                notifyStatus("❌ 发送失败: " + e.getMessage());
            }
        });
    }

    // ═══════════════════════════════════════════════════════
    // 内部：帧处理分发
    // ═══════════════════════════════════════════════════════

    private void registerFrameHandler() {
        node.setOnFrame((conn, frame) -> {
            switch (frame.type()) {
                case HELLO -> handleHello(conn, frame);
                case HELLO_ACK -> handleHelloAck(conn, frame);
                case TEXT -> handleText(conn, frame);
                case ACK -> handleAck(frame);
                case BYE -> handleBye(conn);
                default -> log.debug("暂未处理的消息类型: {}", frame.type());
            }
        });

        node.setOnDisconnect((conn, reason) -> {
            log.info("{} 断开: {}", conn, reason);
            // 更新 contacts.is_online = 0
            virtualThread(() -> {
                try (var dbConn = DatabaseManager.getConnection()) {
                    new ContactRepository(dbConn).updateOnline(conn.peerDeviceId(), false);
                } catch (Exception ignored) {}
            });
            notifyStatus("⚠️ " + conn + " 已断开");
        });

        node.setOnConnectionAccepted(conn -> {
            // 等 HELLO 到达后再处理（handleHello 里）
            log.info("入站连接: {}", conn);
        });
    }

    private void handleHello(PeerConnection conn, Frame frame) {
        try {
            JsonObject payload = JsonParser.parseString(frame.payloadAsString()).getAsJsonObject();
            String peerDeviceId = payload.get("deviceId").getAsString();
            String peerNickname = payload.get("nickname").getAsString();
            int peerListenPort = payload.get("listenPort").getAsInt();
            String peerIp = conn.peerIp();

            conn.setPeerInfo(peerDeviceId, peerNickname);

            log.info("收到 HELLO: {} ({}) from {}", peerNickname, peerDeviceId, peerIp);

            // 1. 存好友（upsert）
            try (var dbConn = DatabaseManager.getConnection()) {
                new ContactRepository(dbConn).insertOrUpdate(peerDeviceId, peerNickname, peerIp, peerListenPort);
                new ContactRepository(dbConn).updateOnline(peerDeviceId, true);
            }

            // 2. 创建/确保单聊会话存在
            try (var dbConn = DatabaseManager.getConnection()) {
                String contactId = new ContactRepository(dbConn)
                    .findByDeviceId(peerDeviceId).map(c -> c.id()).orElse(null);
                if (contactId != null) {
                    new ConversationRepository(dbConn).createPrivate(deviceId, peerDeviceId, contactId);
                }
            }

            // 3. 发送 HELLO_ACK（接受）
            JsonObject ack = new JsonObject();
            ack.addProperty("status", "ACCEPTED");
            ack.addProperty("nickname", this.nickname);
            ack.addProperty("deviceId", this.deviceId);
            ack.addProperty("timestamp", System.currentTimeMillis());
            conn.send(Frame.of(MessageType.HELLO_ACK, GSON.toJson(ack)));

            notifyStatus("✅ 好友 " + peerNickname + " (" + peerDeviceId + ") 已连接");

        } catch (Exception e) {
            log.error("HELLO 处理异常", e);
        }
    }

    private void handleHelloAck(PeerConnection conn, Frame frame) {
        try {
            JsonObject payload = JsonParser.parseString(frame.payloadAsString()).getAsJsonObject();
            String status = payload.get("status").getAsString();

            if ("ACCEPTED".equals(status)) {
                String peerDeviceId = payload.get("deviceId").getAsString();
                String peerNickname = payload.get("nickname").getAsString();
                conn.setPeerInfo(peerDeviceId, peerNickname);
                log.info("HELLO_ACK ACCEPTED: {} ({})", peerNickname, peerDeviceId);
                notifyStatus("✅ " + peerNickname + " 接受了连接");
                // 存好友
                try (var dbConn = DatabaseManager.getConnection()) {
                    new ContactRepository(dbConn).insertOrUpdate(peerDeviceId, peerNickname, conn.peerIp(), conn.peerPort());
                    new ContactRepository(dbConn).updateOnline(peerDeviceId, true);
                }
            } else {
                int rejectCode = payload.has("rejectCode") ? payload.get("rejectCode").getAsInt() : 255;
                HelloRejectCode code = HelloRejectCode.fromCode(rejectCode);
                String reason = payload.has("reason") ? payload.get("reason").getAsString() : code.defaultMessage();
                log.warn("HELLO_ACK REJECTED: code={}, reason={}", rejectCode, reason);
                notifyStatus("❌ " + reason);
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

            // 1. 回复 ACK
            JsonObject ack = new JsonObject();
            ack.addProperty("msgId", msgId);
            ack.addProperty("timestamp", System.currentTimeMillis());
            ack.addProperty("status", "OK");
            conn.send(Frame.of(MessageType.ACK, GSON.toJson(ack)));

            // 2. 存本地 + 更新会话预览
            saveLocalMessage(msgId, senderDeviceId, content, MessageDirection.RECEIVED, MessageStatus.DELIVERED);

            // 3. 通知 UI
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
            String msgId = payload.get("msgId").getAsString();
            log.debug("收到 ACK: msgId={}", msgId);
        } catch (Exception e) {
            log.error("ACK 处理异常", e);
        }
    }

    private void handleBye(PeerConnection conn) {
        log.info("收到 BYE from {}", conn.peerDeviceId());
        conn.close();
    }

    // ═══════════════════════════════════════════════════════
    // 工具方法
    // ═══════════════════════════════════════════════════════

    private void saveLocalMessage(String msgId, String peerDeviceId, String content,
                                   MessageDirection direction, MessageStatus status) {
        long now = System.currentTimeMillis();
        try (var dbConn = DatabaseManager.getConnection()) {
            // 算 conversationId（双方 deviceId 排序拼接）
            String convId = java.util.stream.Stream.of(deviceId, peerDeviceId)
                .sorted().collect(java.util.stream.Collectors.joining("-"));

            // 找对端昵称
            String senderNickname;
            if (direction == MessageDirection.SENT) {
                senderNickname = this.nickname;
            } else {
                senderNickname = new ContactRepository(dbConn)
                    .findByDeviceId(peerDeviceId)
                    .map(ContactRepository.Contact::nickname)
                    .orElse(peerDeviceId);
            }

            // INSERT OR IGNORE（msgId 重复 → 自动跳过）
            new MessageRepository(dbConn).insertOrIgnore(new MessageRepository.MessageRow(
                msgId, convId, peerDeviceId, senderNickname,
                "TEXT", content, null, null, null,
                null, now, now, direction, status
            ));

            // 更新会话预览
            String preview = content.length() > 50 ? content.substring(0, 50) + "..." : content;
            new ConversationRepository(dbConn).updateLastMessage(convId, preview, "TEXT");

        } catch (SQLException e) {
            log.error("消息入库失败", e);
        }
    }

    private void notifyStatus(String msg) {
        log.info("状态: {}", msg);
        if (onStatusUpdate != null) {
            onStatusUpdate.accept(msg);
        }
    }

    private void virtualThread(Runnable r) {
        Thread.ofVirtual().start(r);
    }

    // ── 公共 getter ──

    public String deviceId() { return deviceId; }
    public String nickname() { return nickname; }
    public int listenPort() { return listenPort; }
    public P2PNode node() { return node; }

    /** 收到 TEXT 时的事件数据（给 UI 层）。 */
    public record IncomingText(String senderDeviceId, String content, long timestamp) {}
}
