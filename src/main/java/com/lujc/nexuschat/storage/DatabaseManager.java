package com.lujc.nexuschat.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SQLite 连接管理 + BusyException 重试 —— 与 ARCHITECTURE.md §7.5 保持一致。
 * <p>
 * 每个虚拟线程独享一个 Connection（ThreadLocal），SQLite WAL 模式 + PRAGMA busy_timeout + withRetry
 * 三重保险，彻底解决并发写入锁竞争。
 */
public class DatabaseManager {

    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);

    private static final String DATA_DIR = System.getProperty("user.home") + File.separator + ".nexuschat";
    private static final String DB_PATH = DATA_DIR + File.separator + "nexuschat.db";
    private static final String JDBC_URL = "jdbc:sqlite:" + DB_PATH;

    // SQLITE_BUSY 错误码
    private static final int SQLITE_BUSY = 5;
    private static final int MAX_RETRIES = 5;

    // ThreadLocal：每个线程一个 Connection（第一次使用时才创建）
    private static final ThreadLocal<Connection> CONN_HOLDER = new ThreadLocal<>();

    private static final AtomicInteger OPENED_COUNT = new AtomicInteger(0);

    private DatabaseManager() {}

    public static Connection getConnection() {
        Connection conn = CONN_HOLDER.get();
        if (conn == null) {
            try {
                conn = DriverManager.getConnection(JDBC_URL);
                initPragmas(conn);
            } catch (SQLException e) {
                throw new RuntimeException("SQLite 连接初始化失败: " + JDBC_URL, e);
            }
            CONN_HOLDER.set(conn);
        }
        return conn;
    }

    /**
     * 释放当前线程持有的 Connection。
     * 虚拟线程跑完一次任务后必须调用，否则每个任务都会遗留一条连接（线程结束也不会自动关）。
     */
    public static void releaseCurrent() {
        Connection conn = CONN_HOLDER.get();
        if (conn == null) return;
        CONN_HOLDER.remove();
        try {
            if (!conn.isClosed()) conn.close();
            OPENED_COUNT.decrementAndGet();
        } catch (SQLException e) {
            log.debug("关闭 Connection 失败: {}", e.getMessage());
        }
    }

    /** 应用数据根目录 ~/.nexuschat（文件传输的 temp/ 与 files/ 都放在它下面）。 */
    public static File dataDir() {
        return new File(DATA_DIR);
    }

    private static void initPragmas(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode  = WAL");
            stmt.execute("PRAGMA foreign_keys  = ON");       // SQLite 默认关，必须手动开
            stmt.execute("PRAGMA synchronous   = NORMAL");
            stmt.execute("PRAGMA busy_timeout  = 30000");  // 锁等待 30s
            stmt.execute("PRAGMA cache_size    = -64000");
        }
        OPENED_COUNT.incrementAndGet();
        log.debug("SQLite Connection 初始化，当前总数: {}", OPENED_COUNT.get());
    }

    // ── SQLITE_BUSY 重试装饰器 ────────────────────────────────────

    @FunctionalInterface
    public interface SqlSupplier<T> { T get() throws SQLException; }

    @FunctionalInterface
    public interface SqlRunnable { void run() throws SQLException; }

    /**
     * 执行一个数据库操作，遇到 SQLITE_BUSY 自动指数退避重试（最多 5 次：50ms → 800ms）。
     */
    public static <T> T withRetry(SqlSupplier<T> action) throws SQLException {
        int attempt = 0;
        while (true) {
            try {
                return action.get();
            } catch (SQLException e) {
                if (e.getErrorCode() != SQLITE_BUSY || attempt >= MAX_RETRIES) {
                    throw e;
                }
                attempt++;
                long sleepMs = 50L * (1L << (attempt - 1));
                log.debug("SQLITE_BUSY 重试 attempt={}, sleep={}ms", attempt, sleepMs);
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("重试被中断", ie);
                }
            }
        }
    }

    /**
     * 无返回值版本。
     */
    public static void withRetry(SqlRunnable action) throws SQLException {
        withRetry(() -> {
            action.run();
            return null;
        });
    }

    // ── 启动时一次性建表（幂等）──────────────────────────────────

    /**
     * 建目录 + 建 10 张表（CREATE TABLE IF NOT EXISTS 天然幂等，可重复执行）。
     * 直接用 ThreadLocal Connection，不额外新开。
     */
    public static synchronized void initSchema() throws SQLException {
        new File(DATA_DIR).mkdirs();
        log.info("初始化 SQLite Schema: {}", DB_PATH);

        Connection conn = getConnection();
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(SqlConstants.APP_CONFIG_CREATE);
            stmt.execute(SqlConstants.CONTACTS_CREATE);
            stmt.execute(SqlConstants.CONTACT_GROUPS_CREATE);
            stmt.execute(SqlConstants.CONTACT_GROUP_MEMBERSHIP_CREATE);
            stmt.execute(SqlConstants.CONVERSATIONS_CREATE);
            stmt.execute(SqlConstants.MESSAGES_CREATE);
            stmt.execute(SqlConstants.FILE_TRANSFERS_CREATE);
            stmt.execute(SqlConstants.GROUPS_CREATE);
            stmt.execute(SqlConstants.GROUP_MEMBERS_CREATE);
            stmt.execute(SqlConstants.GROUP_LEADER_LEDGER_CREATE);

            // app_config 初始数据（INSERT OR IGNORE 防重复）
            long now = System.currentTimeMillis();
            stmt.execute(SqlConstants.APP_CONFIG_INIT.formatted(now, now, now, now, now));
        }
        log.info("Schema 初始化完成");
    }
}
