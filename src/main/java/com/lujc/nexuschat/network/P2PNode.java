package com.lujc.nexuschat.network;

import com.lujc.nexuschat.protocol.Frame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 点对点网络节点 —— 与 ARCHITECTURE.md §4 + §3.6 保持一致。
 * <p>
 * 职责：
 * <ul>
 *   <li>ServerSocket 监听，接受入站连接（在虚拟线程池里跑 accept 循环）</li>
 *   <li>主动连接对端（connectTo）</li>
 *   <li>维护 deviceId → PeerConnection 映射（唯一连接管理）</li>
 *   <li>双向同时连接冲突解决：HELLO 里带 listenPort，端口大的保留被动连接</li>
 * </ul>
 */
public class P2PNode {

    private static final Logger log = LoggerFactory.getLogger(P2PNode.class);

    private ServerSocket serverSocket;
    private int listenPort;
    private final String deviceId;
    private final String nickname;

    // deviceId → PeerConnection（唯一）
    private final Map<String, PeerConnection> connections = new ConcurrentHashMap<>();

    // Java 25 虚拟线程池
    private final ExecutorService virtualPool = Executors.newVirtualThreadPerTaskExecutor();

    // ── 回调
    private java.util.function.Consumer<PeerConnection> onConnectionAccepted;
    private java.util.function.BiConsumer<PeerConnection, Frame> onFrame;
    private java.util.function.BiConsumer<PeerConnection, String> onDisconnect;

    public P2PNode(String deviceId, String nickname) {
        this.deviceId = deviceId;
        this.nickname = nickname;
    }

    // ── 启动监听 ──

    /**
     * 启动 ServerSocket，在虚拟线程里跑 accept 循环。
     */
    public synchronized void startListening(int port) throws IOException {
        if (serverSocket != null && !serverSocket.isClosed()) {
            throw new IllegalStateException("已在监听端口 " + listenPort);
        }
        this.listenPort = port;
        serverSocket = new ServerSocket(port);
        log.info("P2PNode 开始监听 port={}, deviceId={}, nickname={}", port, deviceId, nickname);

        virtualPool.execute(this::acceptLoop);
    }

    private void acceptLoop() {
        try {
            while (!serverSocket.isClosed()) {
                Socket socket = serverSocket.accept();
                PeerConnection conn = new PeerConnection(socket);
                wireCallbacks(conn);
                log.info("入站连接 → {}", socket.getRemoteSocketAddress());

                // 启动虚拟线程跑读循环
                virtualPool.execute(conn::runReadLoop);

                if (onConnectionAccepted != null) {
                    onConnectionAccepted.accept(conn);
                }
            }
        } catch (IOException e) {
            if (!serverSocket.isClosed()) {
                log.error("accept 循环异常", e);
            }
        }
    }

    // ── 主动连接 ──

    /**
     * 主动连接对端。返回的 PeerConnection 已经配置好回调并启动了读循环。
     */
    public PeerConnection connectTo(String host, int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5000);
        PeerConnection conn = new PeerConnection(socket);
        wireCallbacks(conn);
        log.info("主动连接 → {}:{}", host, port);

        virtualPool.execute(conn::runReadLoop);
        return conn;
    }

    // ── 回调设置 ──

    public void setOnConnectionAccepted(java.util.function.Consumer<PeerConnection> c) {
        this.onConnectionAccepted = c;
    }

    public void setOnFrame(java.util.function.BiConsumer<PeerConnection, Frame> onFrame) {
        this.onFrame = onFrame;
    }

    public void setOnDisconnect(java.util.function.BiConsumer<PeerConnection, String> onDisconnect) {
        this.onDisconnect = onDisconnect;
    }

    private void wireCallbacks(PeerConnection conn) {
        conn.setOnFrame((c, frame) -> {
            // 缓存
            String peerId = c.peerDeviceId();
            if (peerId != null) connections.put(peerId, c);

            // 双向连接冲突解决：如果已有一条到这个 deviceId 的连接，按端口比较关闭一条
            maybeResolveDuplicateConnection(c);

            if (onFrame != null) onFrame.accept(c, frame);
        });
        conn.setOnDisconnect(c -> {
            String id = c.peerDeviceId();
            if (id != null) connections.remove(id, c);
            if (onDisconnect != null) onDisconnect.accept(c, "socket closed");
        });
    }

    // ── 双向同时连接冲突解决（与 ARCH §3.6 一致）──────────────────────

    /**
     * 收到 HELLO 后，检查 connections 里是否已有到这个对端的连接。
     * 如果有，比较双方 listenPort：端口大的保留被动连接（被 accept 的那条），关掉主动发起的那条。
     */
    private void maybeResolveDuplicateConnection(PeerConnection incoming) {
        String peerId = incoming.peerDeviceId();
        if (peerId == null) return; // 还没握手完成，跳过

        PeerConnection existing = connections.get(peerId);
        if (existing == null || existing == incoming) return;

        int myPort = this.listenPort;
        int peerPort = incoming.peerPort();

        boolean keepIncoming;
        if (myPort != peerPort) {
            // 端口大的保留被动连接（ServerSocket accept 的那条）
            // incoming 就是被 accept 的那条（我们这边的 ServerSocket accept），所以 keepIncoming = myPort > peerPort
            keepIncoming = myPort > peerPort;
        } else {
            // 端口相同极小概率，退化成 deviceId 字典序
            keepIncoming = this.deviceId.compareTo(peerId) > 0;
        }

        if (keepIncoming) {
            log.info("双向连接冲突：保留被动连接 {}, 关闭主动连接", incoming, existing);
            existing.close();
            connections.put(peerId, incoming);
        } else {
            log.info("双向连接冲突：关闭被动连接 {}, 复用主动连接", incoming, existing);
            incoming.close();
        }
    }

    // ── 管理方法 ──

    public Map<String, PeerConnection> connections() { return Map.copyOf(connections); }

    public PeerConnection getConnection(String peerDeviceId) {
        return connections.get(peerDeviceId);
    }

    public int listenPort() { return listenPort; }
    public String deviceId() { return deviceId; }
    public String nickname() { return nickname; }

    public void stop() {
        virtualPool.shutdown();
        connections.values().forEach(PeerConnection::close);
        connections.clear();
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}
    }
}
