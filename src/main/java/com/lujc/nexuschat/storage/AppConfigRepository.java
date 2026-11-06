package com.lujc.nexuschat.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * app_config 表 CRUD —— 键值对配置存储。
 */
public class AppConfigRepository {

    private final Connection conn;

    public AppConfigRepository(Connection conn) {
        this.conn = conn;
    }

    public Optional<String> get(String key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SqlConstants.APP_CONFIG_GET)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString("value")) : Optional.empty();
            }
        }
    }

    public int getInt(String key, int defaultValue) throws SQLException {
        return get(key).map(v -> {
            try { return Integer.parseInt(v); } catch (NumberFormatException e) { return defaultValue; }
        }).orElse(defaultValue);
    }

    public void set(String key, String value) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SqlConstants.APP_CONFIG_UPSERT)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    public void setInt(String key, int value) throws SQLException {
        set(key, String.valueOf(value));
    }
}
