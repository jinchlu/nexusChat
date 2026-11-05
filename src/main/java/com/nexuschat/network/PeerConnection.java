package com.nexuschat.network;

import com.nexuschat.enums.MessageType;
import com.nexuschat.protocol.Frame;
import com.nexuschat.protocol.FrameCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Socket;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 与一个对端的点对点连接 —— 与 ARCHITECTURE.md §4 线程模型保持一致。
 * <p>
 * 每个 PeerConnection 独立一个虚拟线程做读循环（粘包处理由 FrameCodec 负责）。
 * 写入由调用方（业务线程）直接调用 {@link #send}，Socket 写入本身是线程安全的。
 */
public class PeerConnection implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PeerConnection.class);

    private final Socket socket;
    private final String peerIp;
    private final int peerPort;
    private String peerDeviceId;
    private String peerNickname;

    private volatile boolean closed = false;
    private long lastActivityAt = System.currentTimeMillis();

    // ── 回调 ──
    private BiConsumer<PeerConnection, Frame> onFrame;
    private Consumer<PeerConnection> onDisconnect;

    public PeerConnection(Socket socket) {
        this.socket = socket;
        this.peerIp = socket.getInetAddress().getHostAddress();
        this.peerPort = socket.getPort();
    }

    // ── 设置回调 ──

    public void setOnFrame(BiConsumer<PeerConnection, Frame> onFrame) {
        this.onFrame = onFrame;
    }

    public void setOnDisconnect(Consumer<PeerConnection> onDisconnect) {
        this.onDisconnect = onDisconnect;
    }

    // ── 握手后由业务层填充对端信息 ──

    public void setPeerInfo(String deviceId, String nickname) {
        this.peerDeviceId = deviceId;
        this.peerNickname = nickname;
    }

    // ── 属性 ──

    public Socket socket() { return socket; }
    public String peerIp() { return peerIp; }
    public int peerPort() { return peerPort; }
    public String peerDeviceId() { return peerDeviceId; }
    public String peerNickname() { return peerNickname; }
    public boolean isClosed() { return closed || socket.isClosed(); }
    public long lastActivityAt() { return lastActivityAt; }

    public void touch() { lastActivityAt = System.currentTimeMillis(); }

    // ── 写入：同步发送 ──

    public synchronized void send(Frame frame) throws IOException {
        if (isClosed()) throw new IOException("连接已关闭");
        FrameCodec.encode(frame, socket.getOutputStream());
        lastActivityAt = System.currentTimeMillis();
    }

    // ── 虚拟线程读循环 ──

    /**
     * 启动读循环，在调用它的那个虚拟线程里阻塞运行。读循环不会创建新线程，
     * 由 P2PNode 统一用虚拟线程池调度。
     */
    public void runReadLoop() {
        log.info("启动读循环 → {}:{}", peerIp, peerPort);
        try {
            while (!closed && !socket.isClosed()) {
                Frame frame = FrameCodec.decode(socket.getInputStream());
                lastActivityAt = System.currentTimeMillis();

                // HEARTBEAT 帧直接响应，不交给业务层
                if (frame.type() == MessageType.HEARTBEAT) {
                    send(Frame.empty(MessageType.HEARTBEAT));
                    continue;
                }

                if (onFrame != null) {
                    onFrame.accept(this, frame);
                }
            }
        } catch (IOException e) {
            if (!closed) {
                log.debug("读循环断开 {}:{} — {}", peerIp, peerPort, e.getMessage());
            }
        } finally {
            close();
            if (onDisconnect != null) {
                onDisconnect.accept(this);
            }
        }
    }

    // ── 断开 ──

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public String toString() {
        return "PeerConnection[" + peerDeviceId + "@" + peerIp + ":" + peerPort + "]";
    }
}
