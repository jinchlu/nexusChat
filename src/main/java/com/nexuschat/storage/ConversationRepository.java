package com.nexuschat.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * conversations 表 CRUD —— 单聊/群聊统一一张表。
 */
public class ConversationRepository {

    private final Connection conn;

    public ConversationRepository(Connection conn) {
        this.conn = conn;
    }

    /**
     * 创建单聊会话 —— conversationId 由双方 deviceId 排序拼接（保证两端一致）。
     */
    public String createPrivate(String myDeviceId, String peerDeviceId, String contactId) throws SQLException {
        String conversationId = java.util.stream.Stream.of(myDeviceId, peerDeviceId)
            .sorted().collect(java.util.stream.Collectors.joining("-"));

        try (PreparedStatement check = conn.prepareStatement(SqlConstants.CONV_FIND_BY_ID)) {
            check.setString(1, conversationId);
            try (ResultSet rs = check.executeQuery()) {
                if (rs.next()) return conversationId; // 已存在
            }
        }

        long now = System.currentTimeMillis();
        try (PreparedStatement ps = conn.prepareStatement(SqlConstants.CONV_INSERT)) {
            ps.setString(1, conversationId);
            ps.setString(2, "PRIVATE");
            ps.setString(3, contactId);
            ps.setObject(4, null);
            ps.setLong(5, now);
            ps.executeUpdate();
        }
        return conversationId;
    }

    public String createGroup(String groupId) throws SQLException {
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = conn.prepareStatement(SqlConstants.CONV_INSERT)) {
            ps.setString(1, groupId);
            ps.setString(2, "GROUP");
            ps.setObject(3, null);
            ps.setString(4, groupId);
            ps.setLong(5, now);
            ps.executeUpdate();
        }
        return groupId;
    }

    public List<ConversationRow> listAll() throws SQLException {
        List<ConversationRow> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(SqlConstants.CONV_LIST);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(mapRow(rs));
        }
        return list;
    }

    public void updateLastMessage(String conversationId, String preview, String msgType) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SqlConstants.CONV_UPDATE_LAST_MSG)) {
            ps.setString(1, preview);
            ps.setString(2, msgType);
            ps.setLong(3, System.currentTimeMillis());
            ps.setString(4, conversationId);
            ps.executeUpdate();
        }
    }

    public record ConversationRow(
        String id, String type, String contactId, String groupId,
        String lastMessagePreview, String lastMessageType,
        long lastMessageTs, int unreadCount, String displayName
    ) {}

    private ConversationRow mapRow(ResultSet rs) throws SQLException {
        return new ConversationRow(
            rs.getString("id"),
            rs.getString("type"),
            rs.getString("contact_id"),
            rs.getString("group_id"),
            rs.getString("last_message_preview"),
            rs.getString("last_message_type"),
            rs.getLong("last_message_ts"),
            rs.getInt("unread_count"),
            rs.getString("display_name")
        );
    }
}
