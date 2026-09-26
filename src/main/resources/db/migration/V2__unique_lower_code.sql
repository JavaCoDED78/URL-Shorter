-- Case-insensitive uniqueness of codes (Task 4.3 decision).
--
-- Aliases are normalized to lowercase before storage, while generated Base62
-- codes are mixed case. Without this index a generated code "ABC" and an
-- alias "abc" could coexist as separate rows yet ambiguously resolve on the
-- /{code} redirect path. PostgreSQL remains the final uniqueness authority;
-- the index is a forward fix on top of V1 and never rewrites it.

CREATE UNIQUE INDEX links_code_lower_uq ON links (lower(code));
