package com.nexuschat.protocol;

import com.nexuschat.enums.MessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 帧编解码器 —— 与 ARCHITECTURE.md §3.4 粘包处理流程保持一致。
 * <p>
 * 写入固定头 + 变长 Payload。读取时先读 8 字节头，解析出 Length，再读满 Length 字节 Payload。
 * 处理半包（一次 read 没读够）和粘包（一次 read 读到多帧）。
 */
public final class FrameCodec {

    private static final Logger log = LoggerFactory.getLogger(FrameCodec.class);

    private FrameCodec() {}

    // ── 编码：Frame → OutputStream ──────────────────────────────────

    public static void encode(Frame frame, OutputStream out) throws IOException {
        DataOutputStream dos = (out instanceof DataOutputStream d) ? d : new DataOutputStream(out);
        dos.writeShort(Frame.MAGIC);            // 2B
        dos.writeShort(frame.type().code());    // 2B
        dos.writeInt(frame.payload().length);   // 4B (大端)
        dos.write(frame.payload());              // NB
        dos.flush();
    }

    // ── 解码：InputStream → Frame（处理半包/粘包）──────────────────────

    /**
     * 从 InputStream 读一帧。阻塞直到一帧完整可用，或抛 IOException（包括对端断开）。
     */
    public static Frame decode(InputStream in) throws IOException {
        DataInputStream dis = (in instanceof DataInputStream d) ? d : new DataInputStream(in);

        // 1. 读 2B Magic
        int magic = dis.readUnsignedShort();
        if (magic != Frame.MAGIC) {
            throw new IOException("非法 Magic: 0x" + Integer.toHexString(magic));
        }

        // 2. 读 2B Type
        int typeCode = dis.readUnsignedShort();
        MessageType type = MessageType.fromCode(typeCode);
        if (type == null) {
            throw new IOException("未知消息类型: 0x" + Integer.toHexString(typeCode));
        }

        // 3. 读 4B Length
        int length = dis.readInt();
        if (length < 0 || length > Frame.MAX_PAYLOAD) {
            throw new IOException("非法 Payload 长度: " + length);
        }

        // 4. 读 NB Payload（readFully 自动处理半包）
        byte[] payload = new byte[length];
        dis.readFully(payload);

        return new Frame(type, payload);
    }
}
