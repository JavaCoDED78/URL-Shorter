# Architecture Decision Records

| ADR | Decision | Status |
|---|---|---|
| [ADR-001](ADR-001-302-instead-of-301.md) | `302 Found` instead of `301 Moved Permanently` for redirects | Accepted |
| [ADR-002](ADR-002-redis-cache-aside.md) | Cache-aside link lookup in Redis | Accepted |
| [ADR-003](ADR-003-redis-click-buffering.md) | Buffer click counts in Redis, persist asynchronously | Accepted |
| [ADR-004](ADR-004-lua-atomic-flush.md) | Atomic counter drain via a Lua script | Accepted |
| [ADR-005](ADR-005-base62-code-generation.md) | Uniform-random Base62 code generation with DB-unique guarantee | Accepted |
| [ADR-006](ADR-006-expiration-semantics.md) | Business expiration carried in the cached value, re-checked on every read | Accepted |
