-- H2 Schema: CData Agent (single-user embedded mode)
-- Auto-created on first startup via spring.sql.init.

-- 对话表
CREATE TABLE IF NOT EXISTS conversation (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    title        VARCHAR(128) NULL,
    createTime   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updateTime   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 对话消息表
CREATE TABLE IF NOT EXISTS conversation_message (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversationId BIGINT NOT NULL,
    role           VARCHAR(16) NOT NULL,
    content        CLOB NOT NULL,
    fileAttachments TEXT NULL,
    chartOption    TEXT NULL,
    createTime     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updateTime     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- 为已有表补充列（表已存在时 CREATE TABLE IF NOT EXISTS 不会生效）
ALTER TABLE conversation_message ADD COLUMN IF NOT EXISTS fileAttachments TEXT NULL;
ALTER TABLE conversation_message ADD COLUMN IF NOT EXISTS chartOption TEXT NULL;
ALTER TABLE conversation_message ADD COLUMN IF NOT EXISTS tokenUsage INT NULL;
ALTER TABLE conversation_message ADD COLUMN IF NOT EXISTS conclusion CLOB NULL;
ALTER TABLE conversation_message ADD COLUMN IF NOT EXISTS renderDocument TEXT NULL;
ALTER TABLE conversation_message ADD COLUMN IF NOT EXISTS renderVersion INT NULL;
CREATE INDEX IF NOT EXISTS idx_msg_conv ON conversation_message(conversationId);

-- 数据文件表
CREATE TABLE IF NOT EXISTS data_file (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    originalFilename VARCHAR(256) NOT NULL,
    storagePath      VARCHAR(1024) NOT NULL,
    fileSize         BIGINT NOT NULL DEFAULT 0,
    rowCount         INT NULL,
    columnMeta       CLOB NULL,
    viewName         VARCHAR(128) NOT NULL,
    status           VARCHAR(32) NOT NULL DEFAULT 'CONVERTING',
    conversationId   BIGINT NULL,
    isDelete         TINYINT DEFAULT 0 NOT NULL,
    contentHash      VARCHAR(64) NULL,
    createTime       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updateTime       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_file_conv ON data_file(conversationId);
ALTER TABLE data_file ADD COLUMN IF NOT EXISTS contentHash VARCHAR(64) NULL;
