package com.nexuschat.protocol;

import com.nexuschat.enums.MessageType;

/**
 * 协议帧 —— 与 ARCHITECTURE.md §3.1 保持一致。
 * <pre>
 *  ┌──────┬──────┬──────────────────┬─────────┐
 *  │ Magic│ Type │  Payload Length  │ Payload │
 *  │  2B  │  2B  │     4B (int32)  │   NB    │
 *  └──────┴──────┴──────────────────┴─────────┘
 * </pre>
 * Magic = 0xCAFE；Length 大端序、不含头本身。
 */
public record Frame(MessageType type, byte[] payload) {

    public static final int MAGIC = 0xCAFE;
    public static final int HEADER_SIZE = 2 + 2 + 4; // 8 bytes
    public static final int MAX_PAYLOAD = 16 * 1024 * 1024; // 16MB 单帧上限

    public Frame {
        if (payload == null) payload = new byte[0];
        if (payload.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("Payload too large: " + payload.length + " > " + MAX_PAYLOAD);
        }
    }

    public static Frame of(MessageType type, String jsonPayload) {
        return new Frame(type, jsonPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static Frame empty(MessageType type) {
        return new Frame(type, new byte[0]);
    }

    public String payloadAsString() {
        return new String(payload, java.nio.charset.StandardCharsets.UTF_8);
    }
}
