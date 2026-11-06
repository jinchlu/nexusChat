package com.lujc.nexuschat.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lujc.nexuschat.enums.MessageDirection;
import com.lujc.nexuschat.enums.MessageStatus;
import com.lujc.nexuschat.enums.MessageType;
import com.lujc.nexuschat.network.PeerConnection;
import com.lujc.nexuschat.protocol.Frame;
import com.lujc.nexuschat.storage.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 文件 / 图片传输服务 —— Phase 2。
 * <p>
 * 协议（见 ARCHITECTURE.md §3.3 / §10.7）：
 * <pre>
 *   FILE_META  (JSON)                     → 对端回 FILE_ACK(chunkIndex = -1)
 *   FILE_CHUNK (4B 大端 chunkIndex + 数据) → 对端回 FILE_ACK(chunkIndex = i)
 *   FILE_DONE  (JSON, 带 sha256)          → 对端校验后回 FILE_ACK(chunkIndex = -2)
 * </pre>
 * 每个分片 64KB，ACK 超时 10s 重发，最多重发 3 次。
 * 接收端把数据先写 temp/*.part，校验 sha256 通过后才移动到 files/ 正式目录。
 */
public class FileTransferService {

    private static final Logger log = LoggerFactory.getLogger(FileTransferService.class);
    private static final Gson GSON = new Gson();

    public static final int CHUNK_SIZE = 64 * 1024;
    private static final long MAX_FILE_SIZE = 4L * 1024 * 1024 * 1024; // 4GB
    private static final long ACK_TIMEOUT_MS = 10_000;
    private static final long DONE_TIMEOUT_MS = 30_000;
    private static final int MAX_RETRY = 3;

    private final String selfDeviceId;
    private final String selfNickname;
    private final Consumer<FileEvent> onEvent;

    /** 每个连接同一时刻只支持一个入站传输（FILE_CHUNK 的载荷里只有分片序号，见协议约定）。 */
    private final Map<PeerConnection, Incoming> incoming = new ConcurrentHashMap<>();
    /** key = transferId + "#" + chunkIndex */
    private final Map<String, CompletableFuture<JsonObject>> pendingAcks = new ConcurrentHashMap<>();

    public FileTransferService(String selfDeviceId, String selfNickname, Consumer<FileEvent> onEvent) {
        this.selfDeviceId = selfDeviceId;
        this.selfNickname = selfNickname;
        this.onEvent = onEvent;
    }

    // ═══════════════════════════════════════════════════════════
    // 对外：发送
    // ═══════════════════════════════════════════════════════════

    public void sendFile(PeerConnection conn, String peerDeviceId, Path file) {
        Thread.ofVirtual().name("file-send-" + file.getFileName()).start(() -> {
            String transferId = UUID.randomUUID().toString();
            String fileName = file.getFileName().toString();
            boolean metaAccepted = false;
            try {
                long size = Files.size(file);
                if (size <= 0) {
                    failTransfer(null, transferId, peerDeviceId, fileName, size, "空文件不支持发送", false);
                    return;
                }
                if (size > MAX_FILE_SIZE) {
                    failTransfer(null, transferId, peerDeviceId, fileName, size, "文件超过 4GB 上限", false);
                    return;
                }

                String mime = Files.probeContentType(file);
                String sha256 = sha256(file);
                int totalChunks = (int) ((size + CHUNK_SIZE - 1) / CHUNK_SIZE);
                String convId = conversationId(selfDeviceId, peerDeviceId);

                saveTransferRow(transferId, convId, fileName, size, null, file.toString(),
                    sha256, totalChunks, 0, "SENT", "PENDING", System.currentTimeMillis(), null, null);

                emit(new FileEvent(FileEvent.Type.STARTED, true, peerDeviceId, transferId, fileName, size, 0, null, null));

                // ── 1. FILE_META，等对端确认接收 ──
                JsonObject meta = new JsonObject();
                meta.addProperty("transferId", transferId);
                meta.addProperty("fileName", fileName);
                meta.addProperty("fileSize", size);
                meta.addProperty("sha256", sha256);
                meta.addProperty("chunkSize", CHUNK_SIZE);
                meta.addProperty("totalChunks", totalChunks);
                if (mime != null) meta.addProperty("mimeType", mime);
                meta.addProperty("senderDeviceId", selfDeviceId);
                meta.addProperty("senderNickname", selfNickname);
                meta.addProperty("timestamp", System.currentTimeMillis());

                JsonObject metaAck = sendAndAwait(conn, Frame.of(MessageType.FILE_META, GSON.toJson(meta)),
                    transferId, -1, ACK_TIMEOUT_MS);
                if (metaAck == null || !"OK".equals(statusOf(metaAck))) {
                    String reason = metaAck == null ? "对端无响应" : reasonOf(metaAck, "对端拒绝接收");
                    failTransfer(conn, transferId, peerDeviceId, fileName, size, reason, false);
                    return;
                }
                metaAccepted = true;

                // ── 2. 逐块发送 + 每块 ACK ──
                long startAt = System.currentTimeMillis();
                long lastDbAt = startAt;
                int lastPct = -1;

                try (InputStream in = new BufferedInputStream(Files.newInputStream(file), CHUNK_SIZE)) {
                    byte[] buf = new byte[CHUNK_SIZE];
                    for (int idx = 0; idx < totalChunks; idx++) {
                        int len = in.readNBytes(buf, 0, CHUNK_SIZE);
                        if (len <= 0) throw new IOException("文件读取提前结束，分片 " + idx);

                        byte[] payload = new byte[4 + len];
                        payload[0] = (byte) (idx >>> 24);
                        payload[1] = (byte) (idx >>> 16);
                        payload[2] = (byte) (idx >>> 8);
                        payload[3] = (byte) idx;
                        System.arraycopy(buf, 0, payload, 4, len);

                        if (!sendChunkWithRetry(conn, transferId, idx, payload)) {
                            failTransfer(conn, transferId, peerDeviceId, fileName, size,
                                "分片 " + idx + " 重发 3 次仍失败", true);
                            return;
                        }

                        long sent = Math.min(size, (long) (idx + 1) * CHUNK_SIZE);
                        int pct = (int) (sent * 100 / size);
                        long now = System.currentTimeMillis();

                        if (pct != lastPct) {
                            lastPct = pct;
                            emit(new FileEvent(FileEvent.Type.PROGRESS, true, peerDeviceId, transferId,
                                fileName, size, sent, null, null));
                        }
                        if (now - lastDbAt >= 500 || idx == totalChunks - 1) {
                            long speed = sent * 1000L / Math.max(1, now - startAt);
                            updateProgressRow(transferId, idx + 1, pct, speed);
                            lastDbAt = now;
                        }
                    }
                }

                // ── 3. FILE_DONE，等对端校验结果 ──
                JsonObject done = new JsonObject();
                done.addProperty("transferId", transferId);
                done.addProperty("sha256", sha256);
                done.addProperty("totalChunks", totalChunks);
                done.addProperty("timestamp", System.currentTimeMillis());

                JsonObject doneAck = sendAndAwait(conn, Frame.of(MessageType.FILE_DONE, GSON.toJson(done)),
                    transferId, -2, DONE_TIMEOUT_MS);
                if (doneAck == null || !"OK".equals(statusOf(doneAck))) {
                    String reason = doneAck == null ? "等待对端校验超时" : reasonOf(doneAck, "对端校验失败");
                    failTransfer(conn, transferId, peerDeviceId, fileName, size, reason, true);
                    return;
                }

                long now = System.currentTimeMillis();
                long speed = size * 1000L / Math.max(1, now - startAt);
                completeTransferRow(transferId, totalChunks, file.toString(), speed);
                saveMessage(transferId, convId, peerDeviceId, fileName, file.toString(), size, mime,
                    MessageDirection.SENT);
                emit(new FileEvent(FileEvent.Type.COMPLETED, true, peerDeviceId, transferId, fileName,
                    size, size, file, null));
                log.info("文件发送完成: {} ({} bytes) → {}", fileName, size, peerDeviceId);

            } catch (Exception e) {
                log.error("文件发送失败: {}", fileName, e);
                failTransfer(conn, transferId, peerDeviceId, fileName, -1, e.getMessage(), metaAccepted);
            } finally {
                // 发送线程结束，释放它持有的 SQLite 连接
                DatabaseManager.releaseCurrent();
            }
        });
    }

    // ═══════════════════════════════════════════════════════════
    // 对外：接收（由 ChatService 的帧分发调用）
    // ═══════════════════════════════════════════════════════════

    public void handleFrame(PeerConnection conn, Frame frame) {
        try {
            switch (frame.type()) {
                case FILE_META -> onMeta(conn, parse(frame));
                case FILE_CHUNK -> onChunk(conn, frame.payload());
                case FILE_ACK -> onAck(parse(frame));
                case FILE_DONE -> onDone(conn, parse(frame));
                case CANCEL_TRANSFER -> onCancel(conn, parse(frame));
                default -> { /* 非文件帧 */ }
            }
        } catch (Exception e) {
            log.error("处理文件帧失败: {}", frame.type(), e);
        }
    }

    // ── 接收：元数据 ──

    private void onMeta(PeerConnection conn, JsonObject meta) throws IOException {
        String transferId = meta.get("transferId").getAsString();
        String fileName = meta.get("fileName").getAsString();
        long size = meta.get("fileSize").getAsLong();
        String sha256 = meta.get("sha256").getAsString();
        int totalChunks = meta.get("totalChunks").getAsInt();
        String mime = meta.has("mimeType") ? meta.get("mimeType").getAsString() : null;
        String senderDeviceId = meta.get("senderDeviceId").getAsString();

        if (size <= 0 || size > MAX_FILE_SIZE) {
            replyAck(conn, transferId, -1, "REJECT", "文件大小非法");
            return;
        }
        if (incoming.containsKey(conn)) {
            replyAck(conn, transferId, -1, "REJECT", "已有进行中的文件接收");
            return;
        }

        Path tempDir = tempDir();
        Path tempPath = tempDir.resolve(transferId + ".part");
        OutputStream out = new BufferedOutputStream(Files.newOutputStream(tempPath), CHUNK_SIZE);

        Incoming in = new Incoming(transferId, fileName, size, sha256, mime, totalChunks,
            tempPath, out, System.currentTimeMillis(), senderDeviceId);
        incoming.put(conn, in);

        String convId = conversationId(selfDeviceId, senderDeviceId);
        saveTransferRow(transferId, convId, fileName, size, null, tempPath.toString(),
            sha256, totalChunks, 0, "RECEIVED", "RECEIVING", System.currentTimeMillis(), null, null);

        replyAck(conn, transferId, -1, "OK", null);
        emit(new FileEvent(FileEvent.Type.STARTED, false, senderDeviceId, transferId, fileName, size, 0, null, null));
        log.info("开始接收文件: {} ({} bytes) from {}", fileName, size, senderDeviceId);
    }

    // ── 接收：分片（4B 大端 index + 原始数据）──

    private void onChunk(PeerConnection conn, byte[] payload) throws IOException {
        Incoming in = incoming.get(conn);
        if (in == null || payload.length < 4) return;

        int idx = ((payload[0] & 0xFF) << 24) | ((payload[1] & 0xFF) << 16)
                | ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);

        // 重复分片：ACK 丢失后发送端会重发同一块，直接再回一次 OK，不重复写盘
        if (idx < in.receivedChunks) {
            replyAck(conn, in.transferId, idx, "OK", null);
            return;
        }
        if (idx != in.receivedChunks) {
            replyAck(conn, in.transferId, idx, "FAIL", "分片顺序错误，期望 " + in.receivedChunks);
            abortIncoming(conn, in, "分片乱序: 收到 " + idx + "，期望 " + in.receivedChunks);
            return;
        }

        try {
            in.out.write(payload, 4, payload.length - 4);
        } catch (IOException e) {
            replyAck(conn, in.transferId, idx, "FAIL", "写入失败");
            abortIncoming(conn, in, "写入临时文件失败: " + e.getMessage());
            return;
        }

        in.receivedChunks++;
        long receivedBytes = Math.min(in.fileSize,
            (long) in.receivedChunks * CHUNK_SIZE);
        int pct = (int) (receivedBytes * 100 / in.fileSize);

        replyAck(conn, in.transferId, idx, "OK", null);

        long now = System.currentTimeMillis();
        if (pct != in.lastPct) {
            in.lastPct = pct;
            emit(new FileEvent(FileEvent.Type.PROGRESS, false, in.senderDeviceId, in.transferId,
                in.fileName, in.fileSize, receivedBytes, null, null));
        }
        if (now - in.lastDbAt >= 500 || in.receivedChunks == in.totalChunks) {
            long speed = receivedBytes * 1000L / Math.max(1, now - in.startedAt);
            updateProgressRow(in.transferId, in.receivedChunks, pct, speed);
            in.lastDbAt = now;
        }
    }

    // ── 接收：完成 + 校验 ──

    private void onDone(PeerConnection conn, JsonObject done) throws IOException {
        Incoming in = incoming.get(conn);
        if (in == null) {
            replyAck(conn, done.has("transferId") ? done.get("transferId").getAsString() : "",
                -2, "FAIL", "没有进行中的传输");
            return;
        }

        in.out.flush();
        in.out.close();
        incoming.remove(conn);

        String actualSha = sha256(in.tempPath);
        boolean ok = in.receivedChunks == in.totalChunks
            && Files.size(in.tempPath) == in.fileSize
            && actualSha.equalsIgnoreCase(in.sha256);

        if (!ok) {
            Files.deleteIfExists(in.tempPath);
            failTransferRow(in.transferId, "sha256/大小校验失败 (实际=" + actualSha + ")");
            replyAck(conn, in.transferId, -2, "FAIL", "校验失败");
            emit(new FileEvent(FileEvent.Type.FAILED, false, in.senderDeviceId, in.transferId,
                in.fileName, in.fileSize, in.fileSize, null, "文件校验失败，请重发"));
            return;
        }

        Path saved = filesDir().resolve(in.transferId + "_" + sanitize(in.fileName));
        Files.move(in.tempPath, saved, StandardCopyOption.REPLACE_EXISTING);

        String convId = conversationId(selfDeviceId, in.senderDeviceId);
        completeTransferRow(in.transferId, in.totalChunks, saved.toString(),
            in.fileSize * 1000L / Math.max(1, System.currentTimeMillis() - in.startedAt));
        saveMessage(in.transferId, convId, in.senderDeviceId, in.fileName, saved.toString(),
            in.fileSize, in.mime, MessageDirection.RECEIVED);

        replyAck(conn, in.transferId, -2, "OK", null);
        emit(new FileEvent(FileEvent.Type.COMPLETED, false, in.senderDeviceId, in.transferId,
            in.fileName, in.fileSize, in.fileSize, saved, null));
        log.info("文件接收完成: {} → {}", in.fileName, saved);
    }

    private void onAck(JsonObject ack) {
        String transferId = ack.get("transferId").getAsString();
        int idx = ack.get("chunkIndex").getAsInt();
        CompletableFuture<JsonObject> f = pendingAcks.get(transferId + "#" + idx);
        if (f != null) f.complete(ack);
    }

    private void onCancel(PeerConnection conn, JsonObject cancel) {
        String transferId = cancel.get("transferId").getAsString();
        Incoming in = incoming.get(conn);
        if (in != null && in.transferId.equals(transferId)) {
            abortIncoming(conn, in, "对端取消传输");
        }
        // 本机作为发送端时：立即唤醒正在等待 ACK 的线程，避免白等 3 次超时
        String reason = reasonOf(cancel, "对端取消传输");
        pendingAcks.forEach((key, future) -> {
            if (key.startsWith(transferId + "#")) {
                JsonObject fail = new JsonObject();
                fail.addProperty("transferId", transferId);
                fail.addProperty("status", "FAIL");
                fail.addProperty("reason", reason);
                future.complete(fail);
            }
        });
    }

    /** 连接断开时清理该连接上未完成的入站传输，避免临时文件与状态永久残留。 */
    public void onConnectionClosed(PeerConnection conn) {
        Incoming in = incoming.remove(conn);
        if (in == null) return;
        try { in.out.close(); } catch (IOException ignored) {}
        try { Files.deleteIfExists(in.tempPath); } catch (IOException ignored) {}
        failTransferRow(in.transferId, "连接已断开");
        emit(new FileEvent(FileEvent.Type.FAILED, false, in.senderDeviceId, in.transferId,
            in.fileName, in.fileSize, 0, null, "连接已断开"));
        log.warn("连接断开，接收中止: {}", in.fileName);
    }

    // ═══════════════════════════════════════════════════════════
    // 内部工具
    // ═══════════════════════════════════════════════════════════

    private boolean sendChunkWithRetry(PeerConnection conn, String transferId, int idx, byte[] payload)
            throws IOException {
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            JsonObject ack = sendAndAwait(conn, new Frame(MessageType.FILE_CHUNK, payload),
                transferId, idx, ACK_TIMEOUT_MS);
            if (ack != null && "OK".equals(statusOf(ack))) return true;
            log.warn("分片 {} 第 {} 次发送未确认: {}", idx, attempt,
                ack == null ? "超时" : reasonOf(ack, "?"));
        }
        return false;
    }

    private JsonObject sendAndAwait(PeerConnection conn, Frame frame, String transferId,
                                    int idx, long timeoutMs) throws IOException {
        String key = transferId + "#" + idx;
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pendingAcks.put(key, future);
        try {
            conn.send(frame);
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return null;
        } finally {
            pendingAcks.remove(key);
        }
    }

    private void replyAck(PeerConnection conn, String transferId, int idx, String status, String reason) {
        JsonObject ack = new JsonObject();
        ack.addProperty("transferId", transferId);
        ack.addProperty("chunkIndex", idx);
        ack.addProperty("status", status);
        ack.addProperty("timestamp", System.currentTimeMillis());
        if (reason != null) ack.addProperty("reason", reason);
        try {
            conn.send(Frame.of(MessageType.FILE_ACK, GSON.toJson(ack)));
        } catch (IOException e) {
            log.debug("回 FILE_ACK 失败: {}", e.getMessage());
        }
    }

    private void abortIncoming(PeerConnection conn, Incoming in, String reason) {
        incoming.remove(conn);
        try { in.out.close(); } catch (IOException ignored) {}
        try { Files.deleteIfExists(in.tempPath); } catch (IOException ignored) {}
        failTransferRow(in.transferId, reason);
        emit(new FileEvent(FileEvent.Type.FAILED, false, in.senderDeviceId, in.transferId,
            in.fileName, in.fileSize, 0, null, reason));
        log.warn("接收中止: {} — {}", in.fileName, reason);
    }

    /**
     * 发送端失败收尾。
     *
     * @param conn       连接，可为 null
     * @param notifyPeer 是否需要给对方发 CANCEL_TRANSFER（对端已进入接收状态时必须发，否则它的
     *                   入站传输会一直占位，导致该连接后续所有文件传输被拒）
     */
    private void failTransfer(PeerConnection conn, String transferId, String peerDeviceId,
                              String fileName, long size, String error, boolean notifyPeer) {
        if (notifyPeer) sendCancel(conn, transferId, error);
        failTransferRow(transferId, error);
        emit(new FileEvent(FileEvent.Type.FAILED, true, peerDeviceId, transferId, fileName,
            Math.max(0, size), 0, null, error));
        log.warn("传输失败: {} — {}", fileName, error);
    }

    private void sendCancel(PeerConnection conn, String transferId, String reason) {
        if (conn == null || conn.isClosed()) return;
        JsonObject cancel = new JsonObject();
        cancel.addProperty("transferId", transferId);
        cancel.addProperty("reason", reason);
        cancel.addProperty("timestamp", System.currentTimeMillis());
        try {
            conn.send(Frame.of(MessageType.CANCEL_TRANSFER, GSON.toJson(cancel)));
        } catch (IOException e) {
            log.debug("发送 CANCEL_TRANSFER 失败: {}", e.getMessage());
        }
    }

    // ── 数据库 ──

    private void saveTransferRow(String transferId, String convId, String fileName, long fileSize,
                                 String savedPath, String tempPath, String sha256, int totalChunks,
                                 int receivedChunks, String direction, String status,
                                 long startedAt, Long finishedAt, String lastError) {
        try {
            new FileTransferRepository(DatabaseManager.getConnection()).insert(
                new FileTransferRepository.TransferRow(
                    transferId, convId, null, fileName, fileSize, savedPath, tempPath, sha256,
                    CHUNK_SIZE, totalChunks, receivedChunks,
                    status.equals("COMPLETED") ? 100 : 0, 0, direction, status,
                    startedAt, finishedAt, lastError));
        } catch (Exception e) {
            log.warn("写入 file_transfers 失败", e);
        }
    }

    private void updateProgressRow(String transferId, int receivedChunks, int pct, long speed) {
        try {
            new FileTransferRepository(DatabaseManager.getConnection())
                .updateProgress(transferId, receivedChunks, pct, speed);
        } catch (Exception e) {
            log.debug("更新传输进度失败: {}", e.getMessage());
        }
    }

    private void completeTransferRow(String transferId, int receivedChunks, String savedPath, long speed) {
        try {
            new FileTransferRepository(DatabaseManager.getConnection())
                .complete(transferId, receivedChunks, savedPath);
        } catch (Exception e) {
            log.warn("更新传输完成状态失败", e);
        }
    }

    private void failTransferRow(String transferId, String error) {
        try {
            new FileTransferRepository(DatabaseManager.getConnection()).fail(transferId, error);
        } catch (Exception e) {
            log.debug("更新传输失败状态失败: {}", e.getMessage());
        }
    }

    /** 落一条 messages 记录（图片存 IMAGE，其他存 FILE）。 */
    private void saveMessage(String transferId, String convId, String peerDeviceId, String fileName,
                             String savedPath, long size, String mime, MessageDirection direction) {
        try {
            String msgType = (mime != null && mime.startsWith("image/")) ? "IMAGE" : "FILE";
            String senderNickname = direction == MessageDirection.SENT
                ? selfNickname
                : new ContactRepository(DatabaseManager.getConnection())
                    .findByDeviceId(peerDeviceId)
                    .map(ContactRepository.Contact::nickname)
                    .orElse(peerDeviceId);

            long now = System.currentTimeMillis();
            new MessageRepository(DatabaseManager.getConnection()).insertOrIgnore(
                new MessageRepository.MessageRow(
                    transferId, convId, peerDeviceId, senderNickname,
                    msgType, fileName, savedPath, size, mime,
                    transferId, now, now, direction, MessageStatus.DELIVERED));

            String preview = ("IMAGE".equals(msgType) ? "[图片] " : "[文件] ") + fileName;
            new ConversationRepository(DatabaseManager.getConnection())
                .updateLastMessage(convId, preview, msgType);
        } catch (Exception e) {
            log.warn("文件消息入库失败", e);
        }
    }

    // ── 杂项 ──

    private static JsonObject parse(Frame frame) {
        return JsonParser.parseString(frame.payloadAsString()).getAsJsonObject();
    }

    private static String statusOf(JsonObject o) {
        return o.has("status") ? o.get("status").getAsString() : "";
    }

    private static String reasonOf(JsonObject o, String fallback) {
        return o.has("reason") ? o.get("reason").getAsString() : fallback;
    }

    private static String conversationId(String a, String b) {
        return Stream.of(a, b).sorted().collect(Collectors.joining("-"));
    }

    private static String sanitize(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private void emit(FileEvent e) {
        if (onEvent != null) onEvent.accept(e);
    }

    private static Path tempDir() throws IOException {
        Path p = DatabaseManager.dataDir().toPath().resolve("temp");
        Files.createDirectories(p);
        return p;
    }

    private static Path filesDir() throws IOException {
        Path p = DatabaseManager.dataDir().toPath().resolve("files");
        Files.createDirectories(p);
        return p;
    }

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[CHUNK_SIZE];
            try (InputStream in = new BufferedInputStream(Files.newInputStream(file), CHUNK_SIZE)) {
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 不可用", e);
        }
    }

    // ── 内部状态 ──

    private static final class Incoming {
        final String transferId;
        final String fileName;
        final long fileSize;
        final String sha256;
        final String mime;
        final int totalChunks;
        final Path tempPath;
        final OutputStream out;
        final long startedAt;
        final String senderDeviceId;

        int receivedChunks = 0;
        int lastPct = -1;
        long lastDbAt = System.currentTimeMillis();

        Incoming(String transferId, String fileName, long fileSize, String sha256, String mime,
                 int totalChunks, Path tempPath, OutputStream out, long startedAt, String senderDeviceId) {
            this.transferId = transferId;
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.sha256 = sha256;
            this.mime = mime;
            this.totalChunks = totalChunks;
            this.tempPath = tempPath;
            this.out = out;
            this.startedAt = startedAt;
            this.senderDeviceId = senderDeviceId;
            this.lastDbAt = startedAt;
        }
    }

    /** 传给 UI 的传输事件。outgoing=true 表示本机发送。 */
    public record FileEvent(Type type, boolean outgoing, String peerDeviceId, String transferId,
                            String fileName, long totalBytes, long transferredBytes,
                            Path savedPath, String error) {
        public enum Type { STARTED, PROGRESS, COMPLETED, FAILED }
    }
}
