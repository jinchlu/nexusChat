package com.nexuschat.enums;

/**
 * 协议消息类型 —— 与 ARCHITECTURE.md §3.2 保持一致。
 * <p>
 * 帧头的 Type 字段（2B）使用这里的 {@link #code} 值，不是 enum ordinal()。
 */
public enum MessageType {

    // ── 连接管理 ──
    HELLO(0x0001, "握手请求"),
    HELLO_ACK(0x0002, "握手响应"),
    HEARTBEAT(0x0003, "心跳包"),
    BYE(0x0004, "主动断开"),
    ACK(0x0005, "通用确认"),

    // ── 好友管理 ──
    FRIEND_REQUEST(0x0010, "添加好友请求"),
    FRIEND_RESPONSE(0x0011, "好友请求响应"),
    FRIEND_LIST_REQ(0x0012, "请求好友列表"),
    FRIEND_LIST_RESP(0x0013, "好友列表响应"),

    // ── 聊天消息 ──
    TEXT(0x0020, "纯文本消息"),
    IMAGE(0x0021, "图片"),

    // ── 文件传输 ──
    FILE_META(0x0030, "文件元数据"),
    FILE_CHUNK(0x0031, "文件数据分片"),
    FILE_ACK(0x0032, "文件分片确认"),
    FILE_DONE(0x0033, "文件传输完成"),
    CANCEL_TRANSFER(0x0034, "取消传输"),

    // ── 群聊消息（Phase 3） ──
    GROUP_CREATE(0x0050, "创建群"),
    GROUP_MESSAGE(0x0051, "群消息"),
    GROUP_INVITE(0x0052, "邀请入群"),
    GROUP_INVITE_ACCEPT(0x0053, "邀请接受"),
    GROUP_INVITE_REJECT(0x0054, "邀请拒绝"),
    GROUP_LEAVE(0x0055, "主动离群"),
    GROUP_DISBAND(0x0056, "群主解散群"),
    GROUP_MEMBER_UPDATE(0x0057, "成员列表更新"),
    GROUP_FILE_META(0x0058, "群文件元数据"),
    GROUP_FILE_CHUNK(0x0059, "群文件分片"),
    GROUP_FILE_ACK(0x005A, "群文件分片确认"),
    GROUP_FILE_DONE(0x005B, "群文件完成");

    private final int code;
    private final String description;

    MessageType(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int code() {
        return code;
    }

    public String description() {
        return description;
    }

    /**
     * 根据 code 反查枚举，找不到返回 null。
     */
    public static MessageType fromCode(int code) {
        for (MessageType t : values()) {
            if (t.code == code) return t;
        }
        return null;
    }
}
