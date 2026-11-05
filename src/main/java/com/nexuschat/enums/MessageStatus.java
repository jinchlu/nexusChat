package com.nexuschat.enums;

/**
 * 消息状态（存 messages.status 字段）。
 */
public enum MessageStatus {
    SENT,       // 已发送
    DELIVERED,  // 对方已确认收到
    READ,       // 对方已读（P2P 无法可靠实现，预留给未来扩展）
    FAILED      // 发送失败
}
