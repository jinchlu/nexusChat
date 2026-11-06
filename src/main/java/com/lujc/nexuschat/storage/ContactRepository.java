package com.lujc.nexuschat.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * contacts 表 CRUD —— Phase 1 用得上。
 */
public class ContactRepository {

    private final Connection conn;

    public ContactRepository(Connection conn) {
        this.conn = conn;
    }

    public void insertOrUpdate(String deviceId, String nickname, String ipAddress, int port) throws SQLException {
        long now = System.currentTimeMillis();
        String id;
        try (PreparedStatement check = conn.prepareStatement(SqlConstants.CONTACTS_FIND_BY_DEVICE_ID)) {
            check.setString(1, deviceId);
            try (ResultSet rs = check.executeQuery()) {
                id = rs.next() ? rs.getString("id") : UUID.randomUUID().toString();
            }
        }

        try (PreparedStatement ps = conn.prepareStatement(SqlConstants.CONTACTS_UPSERT)) {
            ps.setString(1, id);
            ps.setString(2, deviceId);
            ps.setString(3, nickname);
            ps.setObject(4, null);
            ps.setString(5, ipAddress);
            ps.setInt(6, port);
            ps.setLong(7, now);
            ps.executeUpdate();
        }
    }

    public Optional<Contact> findByDeviceId(String deviceId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SqlConstants.CONTACTS_FIND_BY_DEVICE_ID)) {
            ps.setString(1, deviceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    public Optional<Contact> findById(String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SqlConstants.CONTACTS_FIND_BY_ID)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    public List<Contact> findAll() throws SQLException {
        List<Contact> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(SqlConstants.CONTACTS_FIND_ALL);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(mapRow(rs));
        }
        return list;
    }

    public void updateOnline(String deviceId, boolean online) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SqlConstants.CONTACTS_UPDATE_ONLINE)) {
            ps.setInt(1, online ? 1 : 0);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, deviceId);
            ps.executeUpdate();
        }
    }

    public void delete(String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SqlConstants.CONTACTS_DELETE)) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }

    // ── 映射 ──

    public record Contact(
        String id,
        String deviceId,
        String nickname,
        String remark,
        String ipAddress,
        int port,
        boolean online,
        long lastSeenAt,
        long addedAt
    ) {}

    private Contact mapRow(ResultSet rs) throws SQLException {
        return new Contact(
            rs.getString("id"),
            rs.getString("device_id"),
            rs.getString("nickname"),
            rs.getString("remark"),
            rs.getString("ip_address"),
            rs.getInt("port"),
            rs.getInt("is_online") == 1,
            rs.getLong("last_seen_at"),
            rs.getLong("added_at")
        );
    }
}
