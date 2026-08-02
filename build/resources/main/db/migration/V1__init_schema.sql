-- V1__init_schema.sql
-- Initial schema for the URL Shortener service.
-- Target engine: MySQL 8.x, InnoDB, utf8mb4.

CREATE TABLE urls (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    short_code    VARCHAR(32)     CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    original_url  VARCHAR(2048)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at    DATETIME(3)     NULL DEFAULT NULL,
    total_clicks  BIGINT UNSIGNED NOT NULL DEFAULT 0,
    CONSTRAINT pk_urls PRIMARY KEY (id),
    CONSTRAINT uk_urls_short_code UNIQUE KEY (short_code) USING BTREE,
    KEY ix_urls_expires_at (expires_at) USING BTREE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  ROW_FORMAT = DYNAMIC;
