package com.lujc.nexuschat.storage;

import com.lujc.nexuschat.enums.MessageDirection;
import com.lujc.nexuschat.enums.MessageStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * messages 表 CRUD —— 天然 msgId 判重（PRIMARY KEY + INSERT OR IGNORE）。
 */
public class MessageRepository {

    private final Connection conn;

    public MessageRepository(Connection conn) {
        this.conn = conn;
    }

    /**
     * 存入一条消息。如果 msgId 已存在（对端 ACK 丢了重发），INSERT OR IGNORE 返回 0 行但不抛异常。
     * @return 1 = 新消息入库；0 = 重复（已存在）
     */
    public int insertOrIgnore(MessageRow msg) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SqlConstants.MSG_INSERT_OR_IGNORE)) {
            ps.setString(1, msg.id());
            ps.setString(2, msg.conversationId());
            ps.setString(3, msg.senderDeviceId());
            ps.setString(4, msg.senderNickname());
            ps.setString(5, msg.msgType());
            ps.setString(6, msg.content());
            ps.setString(7, msg.mediaPath());
            ps.setObject(8, msg.mediaSize());
            ps.setString(9, msg.mediaMimeType());
            ps.setString(10, msg.transferId());
            ps.setLong(11, msg.timestamp());
            ps.setLong(12, msg.localReceivedAt());
            ps.setString(13, msg.direction().name());
            ps.setString(14, msg.status().name());
            return ps.executeUpdate();
        }
    }

    public List<MessageRow> loadHistory(String conversationId, int limit, int offset) throws SQLException {
        List<MessageRow> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(SqlConstants.MSG_LOAD_HISTORY)) {
            ps.setString(1, conversationId);
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapRow(rs));
            }
        }
        return list;
    }

    public int countUnread(String conversationId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SqlConstants.MSG_COUNT_UNREAD)) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    // ── 行记录 ──

    public record MessageRow(
        String id, String conversationId, String senderDeviceId, String senderNickname,
        String msgType, String content, String mediaPath, Long mediaSize, String mediaMimeType,
        String transferId, long timestamp, long localReceivedAt,
        MessageDirection direction, MessageStatus status
    ) {}

    private MessageRow mapRow(ResultSet rs) throws SQLException {
        return new MessageRow(
            rs.getString("id"),
            rs.getString("conversation_id"),
            rs.getString("sender_device_id"),
            rs.getString("sender_nickname"),
            rs.getString("msg_type"),
            rs.getString("content"),
            rs.getString("media_path"),
            (Long) rs.getObject("media_size"),
            rs.getString("media_mime_type"),
            rs.getString("transfer_id"),
            rs.getLong("timestamp"),
            rs.getLong("local_received_at"),
            MessageDirection.valueOf(rs.getString("direction")),
            MessageStatus.valueOf(rs.getString("status"))
        );
    }
}
