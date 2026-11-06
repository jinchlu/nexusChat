package com.lujc.nexuschat.enums;

/**
 * HELLO_ACK 拒绝码 —— 与 ARCHITECTURE.md §3.3 保持一致。
 */
public enum HelloRejectCode {
    VERSION_MISMATCH(1, "对方版本不兼容，请升级"),
    BLACKLISTED(2, "对方已将你拉黑"),
    ALREADY_CONNECTED(3, "与对方已建立连接，请复用旧连接"),
    TIMEOUT(4, "握手超时，请重试"),
    INTERNAL_ERROR(255, "连接对方时发生未知错误");

    private final int code;
    private final String defaultMessage;

    HelloRejectCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public int code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    public static HelloRejectCode fromCode(int code) {
        for (HelloRejectCode c : values()) {
            if (c.code == code) return c;
        }
        return INTERNAL_ERROR;
    }
}
