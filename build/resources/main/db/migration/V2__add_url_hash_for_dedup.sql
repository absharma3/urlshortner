-- V2__add_url_hash_for_dedup.sql
-- Adds a SHA-256 fingerprint of `original_url` to enable dedup lookups without
-- indexing the full 2048-byte URL column. The hash is stored as 64 hex chars.
-- Nullable so that Flyway can apply this migration to a table with existing
-- rows; a follow-up backfill populates historical rows.

ALTER TABLE urls
    ADD COLUMN original_url_hash CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NULL AFTER original_url,
    ADD INDEX ix_urls_original_url_hash (original_url_hash) USING BTREE;

-- Backfill any pre-existing rows so dedup lookups find them too.
UPDATE urls
   SET original_url_hash = LOWER(HEX(UNHEX(SHA2(original_url, 256))))
 WHERE original_url_hash IS NULL;
