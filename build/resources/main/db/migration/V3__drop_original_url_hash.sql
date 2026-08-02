-- V3__drop_original_url_hash.sql
-- The SHA-256 fingerprint column added in V2 is superseded by deterministic MurmurHash3-derived
-- shortCodes: dedup now happens via `findByShortCode`, so a redundant hash index is dead weight.

ALTER TABLE urls
    DROP INDEX ix_urls_original_url_hash,
    DROP COLUMN original_url_hash;
