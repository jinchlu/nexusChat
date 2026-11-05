# NexusChat 数据库设计

> 版本：v1.0
> 最后更新：2026-11-05
> 关联文档：[ARCHITECTURE.md](ARCHITECTURE.md) §5 本地存储

---

## 1. 概述

| 项 | 值 |
|----|------|
| 数据库 | **SQLite 3.x** |
| 文件位置 | `~/.nexuschat/nexuschat.db` |
| Writable 模式 | WAL（Write-Ahead Logging），高并发读写不阻塞 |
| 编码 | UTF-8 |
| Schema 版本 | 应用内维护 `schema_version`，启动时检查是否需要迁移 |
| **时间戳单位（全局约定）** | **所有 INTEGER 时间字段一律是 Unix 毫秒**（`System.currentTimeMillis()`），无例外 |

**设计原则**：
- **本地主权** —— 所有数据只在本机，不同步、不上云
- **快照优于关联** —— 昵称等可变字段在消息/成员里存快照值，避免历史消息因对方改名而错乱
- **运行时状态与持久化分离** —— `is_online` 等运行时字段心跳驱动更新，不做持久化强依赖
- **P2P 无服务端感知** —— 没有"对端已读"等需要服务端中转的字段

---

## 2. E-R 关系图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              NexusChat Database                             │
└─────────────────────────────────────────────────────────────────────────────┘

  ┌─────────────────────┐        ┌──────────────────────┐
  │    app_config       │        │      contacts        │
  │─────────────────────│        │──────────────────────│
  │ PK  key    TEXT     │        │ PK  id         TEXT  │────┐
  │     value  TEXT     │        │     device_id  TEXT  │    │
  │     updated_at INT  │        │     nickname   TEXT  │    │
  └─────────────────────┘        │     remark      TEXT  │    │
                                 │     ip_address  TEXT  │    │
                                 │     port        INT   │    │
                                 │     is_online   INT   │    │
                                 │     last_seen_at INT  │    │
                                 │     added_at    INT   │    │
                                 └──────────┬───────────┘    │
                                            │                │
                 ┌──────────────────────────┼────────────────┤
                 │                          │                │
                 ▼                          ▼                ▼
  ┌────────────────────────────┐  ┌──────────────────┐  ┌──────────────────┐
  │     conversations           │  │    messages      │  │  file_transfers  │
  │────────────────────────────│  │──────────────────│  │──────────────────│
  │ PK  id            TEXT      │  │ PK  id         TEXT  │  │ PK  transfer_id  TEXT  │
  │     type          TEXT      │  │     conversation_id│ │  │     conversation_id │ │
  │     contact_id    TEXT  ───┼──┤     sender_device_id│ │  │     contact_id   TEXT │
  │     group_id      TEXT  ───┼──┤     sender_nickname │ │  │     file_name    TEXT │
  │     last_preview  TEXT      │  │     msg_type    TEXT  │  │     file_size    INT  │
  │     last_ts       INT       │  │     content      TEXT  │  │     saved_path   TEXT │
  │     unread_count  INT       │  │     media_path   TEXT  │  │     sha256       TEXT │
  │     is_pinned     INT       │  │     transfer_id  TEXT  │  │     progress     INT  │
  │     created_at    INT       │  │     timestamp    INT   │  │     direction    TEXT │
  └──────────┬─────────────────┘  │     direction    TEXT   │  │     status       TEXT │
             │                    │     status       TEXT   │  │     started_at   INT  │
             │                    └──────────────────────┘  │     finished_at  INT  │
             │                                               └──────────────────────┘
             │ FK: conversations.id
             ▼
  ┌──────────────────────────────┐
  │           groups              │
  │──────────────────────────────│
  │ PK  id                   TEXT │──────┐
  │     name                 TEXT │      │
  │     avatar_path          TEXT │      │
  │     my_role              TEXT │      │
  │     status               TEXT │      │
  │     current_leader_id    TEXT │      │
  │     creator_device_id    TEXT │      │
  │     created_at           INT  │      │
  │     disbanded_at         INT  │      │
  └──────────┬───────────────────┘      │
             │ FK: group_id              │ FK: group_id
             ▼                           ▼
  ┌──────────────────────────────┐  ┌────────────────────────────────┐
  │       group_members           │  │     group_leader_ledger        │
  │──────────────────────────────│  │────────────────────────────────│
  │ FK  group_id         TEXT ──┼──┤│ PK  id                      INT  │
  │ PK  device_id        TEXT    │  │     group_id                 TEXT │
  │     nickname         TEXT    │  │     old_leader_id            TEXT │
  │     is_leader        INT     │  │     new_leader_id            TEXT │
  │     joined_at        INT     │  │     reason                   TEXT │
  │     left_at          INT     │  │     timestamp                 INT  │
  │     is_online        INT     │  │     detail                    TEXT │
  │     last_heartbeat_at INT    │  └────────────────────────────────┘
  └──────────────────────────────┘

  ── 关系说明 ──

  contacts (好友)
    ├── 1:N → conversations (一个好友对应一个单聊会话)
    │        conversations.contact_id → contacts.id
    │        单聊通过 conversations.type='PRIVATE' 标记
    │
    ├── 1:N → messages (该好友发送/接收的消息)
    │        messages.sender_device_id → contacts.device_id
    │
    └── 1:N → file_transfers (与该好友的文件传输)
             file_transfers.contact_id → contacts.id

  conversations (会话，统一单聊+群聊)
    ├── type='PRIVATE' → 指向 contacts.id (contact_id)
    ├── type='GROUP'   → 指向 groups.id   (group_id)
    └── 1:N → messages (会话内的消息)

  groups (群)
    ├── 1:N → group_members (群成员)
    └── 1:N → group_leader_ledger (群主变更历史)

  group_members (群成员)
    └── (group_id, device_id) 复合主键

  group_leader_ledger (群主选举/轮换记录)
    └── 用于追溯：什么时候、因为什么、从谁换到谁
```

---

## 3. 表定义（DDL）

### 3.1 app_config — 应用配置

键值对存配置项，方便运行时读写和后续扩展。

```sql
CREATE TABLE IF NOT EXISTS app_config (
    key         TEXT PRIMARY KEY,
    value       TEXT NOT NULL,
    updated_at  INTEGER NOT NULL
);

-- 初始数据
INSERT INTO app_config VALUES ('schema_version', '1',     CAST(strftime('%s','now') AS INTEGER));
INSERT INTO app_config VALUES ('node.listen.port', '15000', CAST(strftime('%s','now') AS INTEGER));
INSERT INTO app_config VALUES ('node.nickname', 'NexusChat User', CAST(strftime('%s','now') AS INTEGER));
INSERT INTO app_config VALUES ('node.data.dir', '~/.nexuschat', CAST(strftime('%s','now') AS INTEGER));
INSERT INTO app_config VALUES ('ui.theme', 'dark',        CAST(strftime('%s','now') AS INTEGER));
```

**所有配置项**（见 ARCHITECTURE.md §10.1）都走这一张表，不单独做列。

---

### 3.2 contacts — 好友表

```sql
CREATE TABLE IF NOT EXISTS contacts (
    id              TEXT PRIMARY KEY,               -- UUID: "a1b2c3d4-e5f6-..."
    device_id       TEXT NOT NULL UNIQUE,           -- 对端 8 字符十六进制指纹
    nickname        TEXT NOT NULL,                  -- 握手时收到的对端昵称（从 HELLO Payload）
    remark          TEXT,                           -- 本地备注名（不和对端同步）
    ip_address      TEXT,                           -- 最后一次已知 IP
    port            INTEGER,                        -- 最后一次已知端口
    avatar_path     TEXT,                           -- 头像本地文件路径（可选）
    is_blocked      INTEGER NOT NULL DEFAULT 0,     -- 0=正常 1=已拉黑
    is_online       INTEGER NOT NULL DEFAULT 0,     -- 运行时：0=离线 1=在线，心跳驱动
    last_seen_at    INTEGER,                       -- 最后一次心跳/消息时间（毫秒）
    added_at        INTEGER NOT NULL                -- 加好友的时间戳（毫秒）
);

CREATE INDEX IF NOT EXISTS idx_contacts_device_id ON contacts(device_id);
CREATE INDEX IF NOT EXISTS idx_contacts_nickname  ON contacts(nickname);
CREATE INDEX IF NOT EXISTS idx_contacts_online    ON contacts(is_online);
```

**关于 device_id**：
- 全局唯一（`UNIQUE` 约束），因为对端重装机后 deviceId 会变（新 MAC + UUID hash）
- 如果重连时发现同一 `device_id` 换了 IP:Port，更新 `ip_address` 和 `port`

---

### 3.3 conversations — 会话表（统一单聊 + 群聊）

**核心设计**：用一张表同时承载单聊和群聊。`type` 字段区分。

```sql
CREATE TABLE IF NOT EXISTS conversations (
    id                  TEXT PRIMARY KEY,           -- UUID
    type                TEXT NOT NULL,              -- 'PRIVATE' 或 'GROUP'
    contact_id          TEXT,                       -- 单聊时：好友 contacts.id（type='PRIVATE' 时必填）
    group_id            TEXT,                       -- 群聊时：群 groups.id（type='GROUP' 时必填）
    last_message_preview TEXT,                      -- 最后一条消息预览（纯文本截取前 50 字）
    last_message_type   TEXT,                        -- 最后一条消息类型：TEXT/IMAGE/FILE/SYSTEM
    last_message_ts     INTEGER,                    -- 最后一条消息时间戳（毫秒）
    unread_count        INTEGER NOT NULL DEFAULT 0, -- 未读消息数
    is_pinned           INTEGER NOT NULL DEFAULT 0, -- 0=未置顶 1=置顶
    is_muted            INTEGER NOT NULL DEFAULT 0, -- 0=未静音 1=静音
    created_at          INTEGER NOT NULL,
    FOREIGN KEY (contact_id) REFERENCES contacts(id) ON DELETE CASCADE,
    FOREIGN KEY (group_id)   REFERENCES groups(id)   ON DELETE CASCADE,
    CHECK (
        (type = 'PRIVATE' AND contact_id IS NOT NULL AND group_id IS NULL) OR
        (type = 'GROUP'   AND group_id   IS NOT NULL AND contact_id IS NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_conversations_type_ts ON conversations(type, last_message_ts DESC);
CREATE INDEX IF NOT EXISTS idx_conversations_contact ON conversations(contact_id);
CREATE INDEX IF NOT EXISTS idx_conversations_group   ON conversations(group_id);
```

**为什么统一成一张表？**
- UI 侧会话列表只需查一张表，不用 UNION
- 消息存储统一走 `messages.conversation_id`
- 加 CHECK 约束保证单聊和群聊互斥

---

### 3.4 messages — 聊天消息表

```sql
CREATE TABLE IF NOT EXISTS messages (
    id                  TEXT PRIMARY KEY,           -- **msgId (UUID)，协议帧里也用这个值**。天然防重发：收到重复 ACK 丢了对方重发的消息时，INSERT 会被主键冲突自动拒绝（Repository 用 INSERT OR IGNORE）
    conversation_id     TEXT NOT NULL,               -- → conversations.id
    sender_device_id    TEXT NOT NULL,               -- 发送方 deviceId（可能是自己）
    sender_nickname     TEXT NOT NULL,               -- 快照值！入消息时存，防止对方改名后历史错乱
    msg_type            TEXT NOT NULL,               -- TEXT / IMAGE / FILE / SYSTEM
    content             TEXT,                        -- TEXT: 文本内容
                                                     -- IMAGE: 描述 JSON {"mime":"image/jpeg","width":800,"height":600}
                                                     -- FILE: 关联 file_transfers.transfer_id 见 media_path
                                                     -- SYSTEM: 系统通知如 "群主已离线，XXX 接任"
    media_path          TEXT,                        -- IMAGE/FILE: 本地文件路径；TEXT/SYSTEM: NULL
    media_size          INTEGER,                     -- 媒体大小（字节）
    media_mime_type     TEXT,                        -- MIME 类型（image/jpeg, application/pdf...）
    transfer_id         TEXT,                        -- FILE: → file_transfers.transfer_id，关联传输记录
    timestamp           INTEGER NOT NULL,             -- 消息原始时间戳（协议帧里带的，Unix 毫秒）
    local_received_at   INTEGER NOT NULL,             -- 本机实际收到的时间戳（毫秒）
    direction           TEXT NOT NULL,                -- SENT = 我发的 / RECEIVED = 我收的 / SYSTEM = 本地产生
    status              TEXT NOT NULL DEFAULT 'SENT'  -- SENT / DELIVERED / READ / FAILED
);

CREATE INDEX IF NOT EXISTS idx_messages_conv_ts      ON messages(conversation_id, timestamp);
CREATE INDEX IF NOT EXISTS idx_messages_sender       ON messages(sender_device_id);
CREATE INDEX IF NOT EXISTS idx_messages_direction    ON messages(direction);
CREATE INDEX IF NOT EXISTS idx_messages_type         ON messages(msg_type);
```

**快照策略**：`sender_nickname` 每次收/发消息时都存一份当时的昵称值，**不做外键关联**。原因：
- 对方随时可能改昵称，历史消息不应该跟着变
- 群成员进出频繁，也不应该依赖 group_members 的当前状态来渲染历史

**status 字段说明**：

| 值 | 含义 |
|----|------|
| SENT | 我发出去了（网络层已发送） |
| DELIVERED | 对方回复了 ACK（单聊 = 直接 ACK；群聊 = 群主 ACK） |
| READ | 对方已读（**P2P 无法可靠实现，预留给未来扩展**） |
| FAILED | 发送失败，标记可重发 |

---

### 3.5 file_transfers — 文件/图片传输表

单聊和群聊的文件传输都走这张表。群聊的话 `conversation_id` 指向群会话，`contact_id` 是发起传输的那个对端。

```sql
CREATE TABLE IF NOT EXISTS file_transfers (
    transfer_id         TEXT PRIMARY KEY,           -- UUID
    conversation_id     TEXT NOT NULL,               -- → conversations.id
    contact_id          TEXT,                        -- → contacts.id（单聊时是对方；群聊时是发送者）
    file_name           TEXT NOT NULL,
    file_size           INTEGER NOT NULL,
    saved_path          TEXT,                        -- 完成后正式存储路径
    temp_path           TEXT,                        -- 传输中 .part 临时文件路径
    sha256              TEXT,                        -- 发送方发来的哈希（校验用）
    chunk_size          INTEGER NOT NULL DEFAULT 65536,
    total_chunks        INTEGER,                    -- ceil(file_size / chunk_size)
    received_chunks     INTEGER NOT NULL DEFAULT 0, -- 已收到的块数
    progress            INTEGER NOT NULL DEFAULT 0,  -- 0-100
    speed               INTEGER DEFAULT 0,           -- 当前传输速度 bytes/s
    direction           TEXT NOT NULL,               -- SEND / RECEIVE
    status              TEXT NOT NULL DEFAULT 'PENDING',
                                                     -- PENDING / PREPARING / TRANSFERRING / PAUSED / DONE / FAILED / CANCELLED
    started_at          INTEGER,
    finished_at         INTEGER,
    last_error          TEXT,                        -- 最后一次错误描述（FAILED 时有用）
    FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE,
    FOREIGN KEY (contact_id)      REFERENCES contacts(id)      ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_transfers_conv     ON file_transfers(conversation_id);
CREATE INDEX IF NOT EXISTS idx_transfers_status   ON file_transfers(status);
CREATE INDEX IF NOT EXISTS idx_transfers_contact  ON file_transfers(contact_id);
```

**为什么群聊的 file_transfers 也存 contact_id？** 因为群文件传输里，文件通常是由某个成员发起的，contact_id 指向那个发送者。但**真正的转发是群主做的**，所以群主本地的 file_transfers 记录里 direction='SEND'（因为在群主看来是他在转发给其他人），而接收成员的 contact_id 是群主。这个语义在 UI 上合理：群主转发时，在他的文件列表里看到"转发给 X 的文件"。

---

### 3.6 groups — 群表

```sql
CREATE TABLE IF NOT EXISTS groups (
    id                      TEXT PRIMARY KEY,       -- UUID: "group-xxx"
    name                    TEXT NOT NULL,
    avatar_path             TEXT,                   -- 群头像本地路径（可选）
    my_role                 TEXT NOT NULL,          -- 'LEADER' 或 'MEMBER'（我在这个群里的角色）
    status                  TEXT NOT NULL DEFAULT 'ACTIVE',
                                                     -- ACTIVE / INACTIVE / DELETED
                                                     -- ACTIVE = 群聊正运转
                                                     -- INACTIVE = 暂时无其他在线成员（只剩我自己是群主），等别人上线自动恢复 ACTIVE
                                                     -- DELETED = 用户手动删除，本地保留历史但界面不再显示
    current_leader_id       TEXT,                   -- 当前群主的 device_id（冗余字段，快速查）
    creator_device_id       TEXT NOT NULL,
    created_at              INTEGER NOT NULL,
    deleted_at              INTEGER,                -- 手动删除时间，NULL = 没删过
    FOREIGN KEY (current_leader_id) REFERENCES contacts(device_id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_groups_status  ON groups(status);
CREATE INDEX IF NOT EXISTS idx_groups_creator ON groups(creator_device_id);
```

**关键设计**：`current_leader_id` 是冗余字段，每次群主变更时同步更新（事务内，和 group_members.is_leader 一起更新）。这样 UI 上显示"群主是谁"不需要 JOIN group_leader_ledger。

---

### 3.7 group_members — 群成员表

```sql
CREATE TABLE IF NOT EXISTS group_members (
    group_id            TEXT NOT NULL,               -- → groups.id
    device_id           TEXT NOT NULL,               -- 对端 device_id
    nickname            TEXT NOT NULL,               -- 快照值：入群时的昵称
    is_leader           INTEGER NOT NULL DEFAULT 0,  -- 0=普通成员 1=当前群主
    joined_at           INTEGER NOT NULL,
    left_at             INTEGER,                     -- 离群时间，NULL = 还在群里
    is_online           INTEGER NOT NULL DEFAULT 0,  -- 运行时
    last_heartbeat_at   INTEGER,                     -- 最后一次心跳时间
    PRIMARY KEY (group_id, device_id),
    FOREIGN KEY (group_id) REFERENCES groups(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_gm_device_id ON group_members(device_id);
CREATE INDEX IF NOT EXISTS idx_gm_online    ON group_members(group_id, is_online);
CREATE INDEX IF NOT EXISTS idx_gm_left      ON group_members(left_at);
```

**永不自动解散**。群聊只有一种删除方式：用户手动操作。即使群只剩 1 人，groups 表和 group_members 表依然存在，只是 status 变成 INACTIVE。用户手动删除后 status = DELETED，本地保留历史但 UI 不再显示。

---

### 3.8 group_leader_ledger — 群主变更历史表

**这是群聊自动选举的关键表**。每次群主发生变化都记录一条，用于追溯、调试、UI 上展示"群主变更通知"。

```sql
CREATE TABLE IF NOT EXISTS group_leader_ledger (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    group_id        TEXT NOT NULL,                   -- → groups.id
    old_leader_id   TEXT,                            -- 变更前群主 device_id（首次创建时 NULL）
    new_leader_id   TEXT NOT NULL,                   -- 变更后群主 device_id
    reason          TEXT NOT NULL,                   -- INIT / AUTO_ELECTION / MANUAL_TRANSFER / LEADER_DISCONNECTED
    timestamp       INTEGER NOT NULL,
    detail          TEXT,                            -- JSON: 选举参数 {"seed":"...","algorithm":"murmur3"}
    FOREIGN KEY (group_id) REFERENCES groups(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_gll_group_ts ON group_leader_ledger(group_id, timestamp DESC);
```

**reason 枚举值**：

| 值 | 场景 |
|----|------|
| `INIT` | 群刚创建，发起人自动成为群主 |
| `AUTO_ELECTION` | 群主离线后，剩余成员一致随机选举新群主 |
| `MANUAL_TRANSFER` | 群主手动转让身份（Phase 5） |
| `LEADER_DISCONNECTED` | 和 AUTO_ELECTION 配合，表示因前群主断连触发了选举 |

---

### 3.9 contact_groups — 好友分组表

**注意**：这是好友标签分组，和群聊的 `groups` 表**完全独立**，没有任何外键关联。

```sql
CREATE TABLE IF NOT EXISTS contact_groups (
    id              TEXT PRIMARY KEY,               -- UUID
    name            TEXT NOT NULL,                  -- 分组名："同事" / "项目A" / "大学同学"
    color           TEXT,                           -- UI 显示色："#4A90D9"
    icon            TEXT,                           -- 图标 emoji 或本地路径（可选）
    sort_order      INTEGER NOT NULL DEFAULT 0,     -- UI 排序用
    created_at      INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_cg_sort ON contact_groups(sort_order);
```

**初始数据**：首次启动时创建一个"全部好友"默认分组？**不创建**，全部好友直接显示在"未分组"区域。分组完全由用户手动创建。

---

### 3.10 contact_group_membership — 好友-分组关联表

多对多：一个好友可以在多个分组里（"张三"同时在"同事"和"项目A"），一个分组可以包含多个好友。

```sql
CREATE TABLE IF NOT EXISTS contact_group_membership (
    group_id        TEXT NOT NULL,                  -- → contact_groups.id
    contact_id      TEXT NOT NULL,                  -- → contacts.id
    added_at        INTEGER NOT NULL,
    PRIMARY KEY (group_id, contact_id),
    FOREIGN KEY (group_id) REFERENCES contact_groups(id) ON DELETE CASCADE,
    FOREIGN KEY (contact_id) REFERENCES contacts(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_cgm_contact ON contact_group_membership(contact_id);
CREATE INDEX IF NOT EXISTS idx_cgm_group   ON contact_group_membership(group_id);
```

**UI 查询示例**：

```sql
-- 查"同事"分组下所有好友
SELECT c.* FROM contacts c
JOIN contact_group_membership m ON c.id = m.contact_id
WHERE m.group_id = ?
ORDER BY c.is_online DESC, c.nickname;

-- 查某个好友的所有分组
SELECT g.* FROM contact_groups g
JOIN contact_group_membership m ON g.id = m.group_id
WHERE m.contact_id = ?
ORDER BY g.sort_order;

-- 查没有分组的好友（UI 显示"未分组"区域）
SELECT * FROM contacts c
WHERE c.id NOT IN (SELECT contact_id FROM contact_group_membership);

-- 按分组聚合的会话列表（主界面左侧通讯录）
SELECT g.name AS group_name, g.color, g.icon,
       COUNT(m.contact_id) AS member_count
FROM contact_groups g
LEFT JOIN contact_group_membership m ON g.id = m.group_id
GROUP BY g.id
ORDER BY g.sort_order;
```

---

## 4. 完整 Schema 关系图（简化版）

```
┌──────────────┐     ┌──────────────────┐
│  app_config  │     │     contacts     │
│ (键值对)      │     │──────────────────│
└──────────────┘     │ PK id            │
                     │  UK device_id     │
                     └────────┬─────────┘
                              │
              ┌───────────────┼───────────────┐
              │               │               │
              ▼               ▼               ▼
┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐
│  conversations    │  │    messages      │  │  file_transfers  │
│──────────────────│  │──────────────────│  │──────────────────│
│ PK id            │  │ PK id            │  │ PK transfer_id    │
│ type (PRI/GROUP) │  │ conversation_id  │  │ conversation_id   │
│ contact_id ──────┼──┤ sender_device_id │  │ contact_id        │
│ group_id   ──────┤  │ transfer_id ─────┼──┤                   │
└────────┬─────────┘  └──────────────────┘  └──────────────────┘
         │
         ▼
┌──────────────────┐     ┌──────────────────┐     ┌──────────────────────┐
│     groups       │     │  group_members    │     │ group_leader_ledger  │
│──────────────────│     │──────────────────│     │──────────────────────│
│ PK id            │     │ PK (group_id,    │     │ PK id (autoinc)       │
│ my_role          │     │      device_id)  │     │ group_id              │
│ current_leader_id│─────┤ is_leader        │     │ old_leader_id         │
│ status           │     │ is_online        │     │ new_leader_id         │
└────────┬─────────┘     └──────────────────┘     │ reason                │
         │                                             └──────────────────────┘
         │
         └──→ group_members.group_id
         └──→ group_leader_ledger.group_id
```

---

## 5. 关键业务查询示例

### 5.1 会话列表（主界面左侧）

```sql
-- 合并单聊和群聊，按最后消息时间倒序
SELECT
    c.id,
    c.type,
    c.last_message_preview,
    c.last_message_ts,
    c.unread_count,
    c.is_pinned,
    -- 单聊：显示对方昵称；群聊：显示群名
    CASE c.type
        WHEN 'PRIVATE' THEN ct.nickname
        WHEN 'GROUP'   THEN g.name
    END AS display_name,
    CASE c.type
        WHEN 'PRIVATE' THEN ct.is_online
        WHEN 'GROUP'   THEN gm.online_count
    END AS online_status
FROM conversations c
LEFT JOIN contacts ct    ON c.type = 'PRIVATE' AND c.contact_id = ct.id
LEFT JOIN groups g       ON c.type = 'GROUP'   AND c.group_id   = g.id
LEFT JOIN (
    SELECT group_id, COUNT(*) AS online_count
    FROM group_members
    WHERE is_online = 1 AND left_at IS NULL
    GROUP BY group_id
) gm ON c.group_id = gm.group_id
WHERE
    -- 单聊：好友没删除；群聊：群没解散
    (c.type = 'PRIVATE' AND ct.id IS NOT NULL) OR
    (c.type = 'GROUP'   AND g.status != 'DELETED')
ORDER BY c.is_pinned DESC, c.last_message_ts DESC;
```

### 5.2 加载会话历史（聊天界面）

```sql
-- 分页加载，每页 50 条
SELECT * FROM messages
WHERE conversation_id = ?
ORDER BY timestamp ASC
LIMIT 50 OFFSET ?;
```

### 5.3 群主变更历史（调试/追溯）

```sql
SELECT gll.*, groups.name AS group_name
FROM group_leader_ledger gll
JOIN groups ON gll.group_id = groups.id
WHERE group_id = ?
ORDER BY timestamp DESC
LIMIT 20;
```

### 5.4 群主离线后，验证各节点选举结果是否一致

```sql
-- 本地生成的选举结果
SELECT new_leader_id, reason, detail
FROM group_leader_ledger
WHERE group_id = ? AND reason = 'AUTO_ELECTION'
ORDER BY timestamp DESC LIMIT 1;
```

### 5.5 查询所有在线好友

```sql
SELECT * FROM contacts
WHERE is_online = 1 AND is_blocked = 0
ORDER BY nickname;
```

---

## 6. SQLite 配置建议

```sql
-- 首次启动时执行（DatabaseManager.init() 里）

PRAGMA journal_mode  = WAL;        -- WAL 模式，读写并发不阻塞
PRAGMA foreign_keys  = ON;         -- 启用外键约束
PRAGMA synchronous   = NORMAL;     -- 平衡性能与安全
PRAGMA cache_size    = -64000;     -- 64MB 页缓存
PRAGMA busy_timeout  = 30000;      -- 30 秒繁忙超时（多线程场景）
PRAGMA temp_store    = MEMORY;     -- 临时表存内存
PRAGMA mmap_size     = 268435456;  -- 256MB mmap，大文件加速
```

---

## 7. 生命周期状态流转

### 7.1 conversations.status

```
PRIVATE 会话：不存在 status 字段（单聊=好友关系，好友还在会话就在）

GROUP 会话：
  ACTIVE ──(只剩自己在线，群主离线)──▶ INACTIVE（等别人上线自动恢复 ACTIVE）
  ACTIVE ──(用户手动删除)──▶ DELETED（本地保留历史但 UI 不显示）
  INACTIVE ──(成员重新上线)──▶ ACTIVE
```

### 7.2 messages.status

```
SENT ──(对端回复 ACK)──▶ DELIVERED
SENT ──(发送失败)──▶ FAILED ──(重发成功)──▶ SENT
SYSTEM 消息：固定 SYSTEM，不走 SENT/DELIVERED 流程
```

### 7.3 file_transfers.status

```
PENDING ──(准备文件/计算哈希)──▶ PREPARING ──(开始传输)──▶ TRANSFERRING
TRANSFERRING ──(对方暂停/手动暂停)──▶ PAUSED ──(恢复)──▶ TRANSFERRING
TRANSFERRING ──(收到 FILE_DONE + sha256 校验通过)──▶ DONE
TRANSFERRING ──(网络错误/超时)──▶ FAILED ──(重试)──▶ TRANSFERRING
TRANSFERRING ──(用户取消)──▶ CANCELLED
```

---

## 附录：与 ARCHITECTURE.md 的对应

| ARCHITECTURE.md 章节 | 本设计文档覆盖 |
|---------------------|---------------|
| §5.1 SQLite 表结构 | 完整替换为本文档 |
| §5.2 文件存储结构 | 不变（见 ARCHITECTURE.md） |
| §3.3 Payload 示例 | messages.content 与 payload 的字段对应 |
| §6.3 service 层 Repository | 每个表对应一个 Repository 类 |
| §12 Phase 1 SQLite 初始化 | DatabaseManager + 建表语句 |
