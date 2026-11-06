package com.lujc.nexuschat.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * file_transfers 表 CRUD —— Phase 2 文件传输记录。
 */
public class FileTransferRepository {

    private final Connection conn;

    public FileTransferRepository(Connection conn) {
        this.conn = conn;
    }

    public void insert(TransferRow row) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SqlConstants.FILE_TRANSFER_INSERT)) {
            ps.setString(1, row.transferId());
            ps.setString(2, row.conversationId());
            ps.setString(3, row.contactId());
            ps.setString(4, row.fileName());
            ps.setLong(5, row.fileSize());
            ps.setString(6, row.savedPath());
            ps.setString(7, row.tempPath());
            ps.setString(8, row.sha256());
            ps.setInt(9, row.chunkSize());
            ps.setInt(10, row.totalChunks());
            ps.setInt(11, row.receivedChunks());
            ps.setInt(12, row.progress());
            ps.setLong(13, row.speed());
            ps.setString(14, row.direction());
            ps.setString(15, row.status());
            ps.setLong(16, row.startedAt());
            ps.setObject(17, row.finishedAt());
            ps.setString(18, row.lastError());
            ps.executeUpdate();
        }
    }

    public void updateProgress(String transferId, int receivedChunks, int progress, long speed) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SqlConstants.FILE_TRANSFER_UPDATE_PROGRESS)) {
            ps.setInt(1, receivedChunks);
            ps.setInt(2, progress);
            ps.setLong(3, speed);
            ps.setString(4, transferId);
            ps.executeUpdate();
        }
    }

    public void complete(String transferId, int receivedChunks, String savedPath) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SqlConstants.FILE_TRANSFER_COMPLETE)) {
            ps.setInt(1, receivedChunks);
            ps.setString(2, savedPath);
            ps.setLong(3, System.currentTimeMillis());
            ps.setString(4, transferId);
            ps.executeUpdate();
        }
    }

    public void fail(String transferId, String error) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SqlConstants.FILE_TRANSFER_FAIL)) {
            ps.setString(1, error);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, transferId);
            ps.executeUpdate();
        }
    }

    public record TransferRow(
        String transferId, String conversationId, String contactId,
        String fileName, long fileSize, String savedPath, String tempPath, String sha256,
        int chunkSize, int totalChunks, int receivedChunks, int progress, long speed,
        String direction, String status, long startedAt, Long finishedAt, String lastError
    ) {}
}
