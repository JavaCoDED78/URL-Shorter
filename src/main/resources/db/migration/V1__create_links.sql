-- Baseline schema: short links.
--
-- Decisions (Task 2.1):
--   * code is the primary key — PostgreSQL owns uniqueness; all access paths
--     (find / redirect / stats / delete / click flush) go through it, so the
--     primary key index is the only index the table needs.
--   * click_count is BIGINT with a non-negative CHECK — buffered Redis
--     counters are flushed into it, so it must never drift below zero.
--   * expires_at is nullable — null means "never expires".
--   * All timestamps are TIMESTAMPTZ (UTC), per the project timezone policy.

CREATE TABLE links (
    code         VARCHAR(64)  NOT NULL,
    original_url TEXT         NOT NULL,
    expires_at   TIMESTAMPTZ  NULL,
    click_count  BIGINT       NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT links_pkey PRIMARY KEY (code),
    CONSTRAINT links_click_count_non_negative CHECK (click_count >= 0)
);
