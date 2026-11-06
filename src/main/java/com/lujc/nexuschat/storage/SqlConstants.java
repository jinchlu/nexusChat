package com.lujc.nexuschat.storage;

/**
 * 所有 SQL 常量集中管理 —— 与 ARCHITECTURE.md §7.2 保持一致。
 * <p>
 * 全部用 Java 15+ Text Blocks 多行字符串，格式和 {@code DATABASE.md} §3 完全同步。
 * 每张表的 CREATE TABLE 后面紧跟常用的 INSERT/SELECT/UPDATE/DELETE。
 */
public final class SqlConstants {

    private SqlConstants() {}

    // ═══════════════════════════════════════════════════════════
    // 3.1 app_config — 应用配置
    // ═══════════════════════════════════════════════════════════

    public static final String APP_CONFIG_CREATE = """
        CREATE TABLE IF NOT EXISTS app_config (
            key         TEXT PRIMARY KEY,
            value       TEXT NOT NULL,
            updated_at  INTEGER NOT NULL
        )""";

    public static final String APP_CONFIG_INIT = """
        INSERT OR IGNORE INTO app_config (key, value, updated_at) VALUES
        ('schema_version', '1', %d),
        ('node.listen.port', '15000', %d),
        ('node.nickname', 'NexusChat User', %d),
        ('node.data.dir', '~/.nexuschat', %d),
        ('ui.theme', 'dark', %d)""";

    public static final String APP_CONFIG_GET = "SELECT value FROM app_config WHERE key = ?";

    public static final String APP_CONFIG_UPSERT = """
        INSERT INTO app_config (key, value, updated_at)
        VALUES (?, ?, ?)
        ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at""";

    // ═══════════════════════════════════════════════════════════
    // 3.2 contacts — 好友
    // ═══════════════════════════════════════════════════════════

    public static final String CONTACTS_CREATE = """
        CREATE TABLE IF NOT EXISTS contacts (
            id              TEXT PRIMARY KEY,
            device_id       TEXT NOT NULL UNIQUE,
            nickname        TEXT NOT NULL,
            remark          TEXT,
            ip_address      TEXT,
            port            INTEGER,
            avatar_path     TEXT,
            is_blocked      INTEGER NOT NULL DEFAULT 0,
            is_online       INTEGER NOT NULL DEFAULT 0,
            last_seen_at    INTEGER,
            added_at        INTEGER NOT NULL
        )""";

    public static final String CONTACTS_INSERT = """
        INSERT INTO contacts
        (id, device_id, nickname, remark, ip_address, port, added_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)""";

    public static final String CONTACTS_UPSERT = """
        INSERT INTO contacts
        (id, device_id, nickname, remark, ip_address, port, added_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(device_id) DO UPDATE SET
            ip_address = excluded.ip_address,
            port = excluded.port,
            last_seen_at = strftime('%s','now') * 1000""";

    public static final String CONTACTS_FIND_BY_ID = "SELECT * FROM contacts WHERE id = ?";
    public static final String CONTACTS_FIND_BY_DEVICE_ID = "SELECT * FROM contacts WHERE device_id = ?";
    public static final String CONTACTS_FIND_ALL = "SELECT * FROM contacts WHERE is_blocked = 0 ORDER BY nickname";

    public static final String CONTACTS_UPDATE_ONLINE = """
        UPDATE contacts SET is_online = ?, last_seen_at = ? WHERE device_id = ?""";

    public static final String CONTACTS_DELETE = "DELETE FROM contacts WHERE id = ?";

    // ═══════════════════════════════════════════════════════════
    // 3.3 contact_groups — 好友分组标签
    // ═══════════════════════════════════════════════════════════

    public static final String CONTACT_GROUPS_CREATE = """
        CREATE TABLE IF NOT EXISTS contact_groups (
            id          TEXT PRIMARY KEY,
            name        TEXT NOT NULL,
            color       TEXT,
            sort_order  INTEGER NOT NULL DEFAULT 0,
            created_at  INTEGER NOT NULL
        )""";

    public static final String CONTACT_GROUPS_INSERT = """
        INSERT INTO contact_groups (id, name, color, sort_order, created_at) VALUES (?, ?, ?, ?, ?)""";

    public static final String CONTACT_GROUPS_FIND_ALL = "SELECT * FROM contact_groups ORDER BY sort_order, name";

    public static final String CONTACT_GROUPS_DELETE = "DELETE FROM contact_groups WHERE id = ?";

    // ═══════════════════════════════════════════════════════════
    // 3.3b contact_group_membership — 好友-分组关联
    // ═══════════════════════════════════════════════════════════

    public static final String CONTACT_GROUP_MEMBERSHIP_CREATE = """
        CREATE TABLE IF NOT EXISTS contact_group_membership (
            group_id    TEXT NOT NULL,
            contact_id  TEXT NOT NULL,
            added_at    INTEGER NOT NULL,
            PRIMARY KEY (group_id, contact_id),
            FOREIGN KEY (group_id)   REFERENCES contact_groups(id) ON DELETE CASCADE,
            FOREIGN KEY (contact_id) REFERENCES contacts(id)        ON DELETE CASCADE
        )""";

    public static final String CGM_ADD_MEMBER = """
        INSERT INTO contact_group_membership (group_id, contact_id, added_at) VALUES (?, ?, ?)""";

    public static final String CGM_REMOVE_MEMBER = """
        DELETE FROM contact_group_membership WHERE group_id = ? AND contact_id = ?""";

    // ═══════════════════════════════════════════════════════════
    // 3.4 conversations — 统一会话
    // ═══════════════════════════════════════════════════════════

    public static final String CONVERSATIONS_CREATE = """
        CREATE TABLE IF NOT EXISTS conversations (
            id                  TEXT PRIMARY KEY,
            type                TEXT NOT NULL,
            contact_id          TEXT,
            group_id            TEXT,
            last_message_preview TEXT,
            last_message_type   TEXT,
            last_message_ts     INTEGER,
            unread_count        INTEGER NOT NULL DEFAULT 0,
            is_pinned           INTEGER NOT NULL DEFAULT 0,
            is_muted            INTEGER NOT NULL DEFAULT 0,
            created_at          INTEGER NOT NULL,
            FOREIGN KEY (contact_id) REFERENCES contacts(id) ON DELETE CASCADE,
            FOREIGN KEY (group_id)   REFERENCES groups(id)   ON DELETE CASCADE,
            CHECK (
                (type = 'PRIVATE' AND contact_id IS NOT NULL AND group_id IS NULL) OR
                (type = 'GROUP'   AND group_id   IS NOT NULL AND contact_id IS NULL)
            )
        )""";

    public static final String CONV_INSERT = """
        INSERT INTO conversations (id, type, contact_id, group_id, created_at)
        VALUES (?, ?, ?, ?, ?)""";

    public static final String CONV_FIND_BY_ID = "SELECT * FROM conversations WHERE id = ?";

    public static final String CONV_LIST = """
        SELECT c.*,
               CASE c.type WHEN 'PRIVATE' THEN ct.nickname WHEN 'GROUP' THEN g.name END AS display_name
        FROM conversations c
        LEFT JOIN contacts ct ON c.type = 'PRIVATE' AND c.contact_id = ct.id
        LEFT JOIN groups    g ON c.type = 'GROUP'   AND c.group_id   = g.id
        WHERE (c.type = 'PRIVATE' AND ct.id IS NOT NULL)
           OR (c.type = 'GROUP'   AND g.status != 'DELETED')
        ORDER BY c.is_pinned DESC, c.last_message_ts DESC""";

    public static final String CONV_UPDATE_LAST_MSG = """
        UPDATE conversations
        SET last_message_preview = ?, last_message_type = ?,
            last_message_ts = ?, unread_count = unread_count + 1
        WHERE id = ?""";

    // ═══════════════════════════════════════════════════════════
    // 3.5 messages — 聊天消息
    // ═══════════════════════════════════════════════════════════

    public static final String MESSAGES_CREATE = """
        CREATE TABLE IF NOT EXISTS messages (
            id                  TEXT PRIMARY KEY,
            conversation_id     TEXT NOT NULL,
            sender_device_id    TEXT NOT NULL,
            sender_nickname     TEXT NOT NULL,
            msg_type            TEXT NOT NULL,
            content             TEXT,
            media_path          TEXT,
            media_size          INTEGER,
            media_mime_type     TEXT,
            transfer_id         TEXT,
            timestamp           INTEGER NOT NULL,
            local_received_at   INTEGER NOT NULL,
            direction           TEXT NOT NULL,
            status              TEXT NOT NULL DEFAULT 'SENT'
        )""";

    // 防重发：INSERT OR IGNORE + PRIMARY KEY 天然去重
    public static final String MSG_INSERT_OR_IGNORE = """
        INSERT OR IGNORE INTO messages
        (id, conversation_id, sender_device_id, sender_nickname,
         msg_type, content, media_path, media_size, media_mime_type,
         transfer_id, timestamp, local_received_at, direction, status)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    public static final String MSG_LOAD_HISTORY = """
        SELECT * FROM messages
        WHERE conversation_id = ?
        ORDER BY timestamp ASC
        LIMIT ? OFFSET ?""";

    public static final String MSG_COUNT_UNREAD = """
        SELECT COUNT(*) FROM messages
        WHERE conversation_id = ? AND direction = 'RECEIVED' AND status = 'SENT'""";

    // ═══════════════════════════════════════════════════════════
    // 3.6 file_transfers — 文件传输记录
    // ═══════════════════════════════════════════════════════════

    public static final String FILE_TRANSFERS_CREATE = """
        CREATE TABLE IF NOT EXISTS file_transfers (
            transfer_id         TEXT PRIMARY KEY,
            conversation_id     TEXT NOT NULL,
            contact_id          TEXT,
            file_name           TEXT NOT NULL,
            file_size           INTEGER NOT NULL,
            saved_path          TEXT,
            temp_path           TEXT,
            sha256              TEXT,
            chunk_size          INTEGER NOT NULL DEFAULT 65536,
            total_chunks        INTEGER,
            received_chunks     INTEGER NOT NULL DEFAULT 0,
            progress            INTEGER NOT NULL DEFAULT 0,
            speed               INTEGER DEFAULT 0,
            direction           TEXT NOT NULL,
            status              TEXT NOT NULL DEFAULT 'PENDING',
            started_at          INTEGER,
            finished_at         INTEGER,
            last_error          TEXT,
            FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE
        )""";

    public static final String FILE_TRANSFER_INSERT = """
        INSERT OR REPLACE INTO file_transfers
        (transfer_id, conversation_id, contact_id, file_name, file_size,
         saved_path, temp_path, sha256, chunk_size, total_chunks,
         received_chunks, progress, speed, direction, status,
         started_at, finished_at, last_error)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    public static final String FILE_TRANSFER_UPDATE_PROGRESS = """
        UPDATE file_transfers
        SET received_chunks = ?, progress = ?, speed = ?
        WHERE transfer_id = ?""";

    public static final String FILE_TRANSFER_COMPLETE = """
        UPDATE file_transfers
        SET received_chunks = ?, progress = 100, saved_path = ?,
            status = 'COMPLETED', finished_at = ?, speed = 0
        WHERE transfer_id = ?""";

    public static final String FILE_TRANSFER_FAIL = """
        UPDATE file_transfers
        SET status = 'FAILED', last_error = ?, finished_at = ?, speed = 0
        WHERE transfer_id = ?""";

    // ═══════════════════════════════════════════════════════════
    // 3.7 groups — 群
    // ═══════════════════════════════════════════════════════════

    public static final String GROUPS_CREATE = """
        CREATE TABLE IF NOT EXISTS groups (
            id                      TEXT PRIMARY KEY,
            name                    TEXT NOT NULL,
            avatar_path             TEXT,
            my_role                 TEXT NOT NULL,
            status                  TEXT NOT NULL DEFAULT 'ACTIVE',
            current_leader_id       TEXT,
            creator_device_id       TEXT NOT NULL,
            created_at              INTEGER NOT NULL,
            deleted_at              INTEGER
        )""";

    public static final String GROUPS_INSERT = """
        INSERT INTO groups (id, name, my_role, status, current_leader_id, creator_device_id, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)""";

    public static final String GROUPS_UPDATE_LEADER = """
        UPDATE groups SET current_leader_id = ?, updated_at = strftime('%s','now')*1000 WHERE id = ?""";

    public static final String GROUPS_FIND_ACTIVE = """
        SELECT * FROM groups WHERE status != 'DELETED' ORDER BY created_at DESC""";

    // ═══════════════════════════════════════════════════════════
    // 3.8 group_members — 群成员
    // ═══════════════════════════════════════════════════════════

    public static final String GROUP_MEMBERS_CREATE = """
        CREATE TABLE IF NOT EXISTS group_members (
            group_id            TEXT NOT NULL,
            device_id           TEXT NOT NULL,
            nickname            TEXT NOT NULL,
            is_leader           INTEGER NOT NULL DEFAULT 0,
            joined_at           INTEGER NOT NULL,
            left_at             INTEGER,
            is_online           INTEGER NOT NULL DEFAULT 0,
            last_heartbeat_at   INTEGER,
            PRIMARY KEY (group_id, device_id),
            FOREIGN KEY (group_id) REFERENCES groups(id) ON DELETE CASCADE
        )""";

    public static final String GM_ADD = """
        INSERT INTO group_members (group_id, device_id, nickname, joined_at)
        VALUES (?, ?, ?, ?)""";

    public static final String GM_LIST = """
        SELECT * FROM group_members WHERE group_id = ? AND left_at IS NULL ORDER BY joined_at""";

    // ═══════════════════════════════════════════════════════════
    // 3.9 group_leader_ledger — 群主变更历史
    // ═══════════════════════════════════════════════════════════

    public static final String GROUP_LEADER_LEDGER_CREATE = """
        CREATE TABLE IF NOT EXISTS group_leader_ledger (
            id              INTEGER PRIMARY KEY AUTOINCREMENT,
            group_id        TEXT NOT NULL,
            old_leader_id   TEXT,
            new_leader_id   TEXT NOT NULL,
            reason          TEXT NOT NULL,
            timestamp       INTEGER NOT NULL,
            FOREIGN KEY (group_id) REFERENCES groups(id) ON DELETE CASCADE
        )""";

    public static final String GLL_INSERT = """
        INSERT INTO group_leader_ledger (group_id, old_leader_id, new_leader_id, reason, timestamp)
        VALUES (?, ?, ?, ?, ?)""";
}
