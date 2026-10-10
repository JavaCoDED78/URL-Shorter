-- Distributed task serialization for the click flush (multi-instance).
--
-- On a multi-pod deployment a single @Scheduled flush would run once per pod,
-- draining the same Redis counters from every replica. This table holds a
-- PostgreSQL-backed lock (ShedLock JdbcLockProvider) so exactly one instance
-- drains at a time; other instances shed the cycle.
--
-- Schema and column names are dictated by the provider (JdbcTemplateLockProvider)
-- and must not drift: name VARCHAR(64) PRIMARY KEY, lock_until / locked_at /
-- locked_by NOT NULL. The provider runs INSERT ... ON CONFLICT (name) DO UPDATE
-- SET lock_until, locked_at, locked_by WHERE shedlock.lock_until <= now; a row
-- whose lock_until is in the future is what makes a second instance shed.
CREATE TABLE IF NOT EXISTS shedlock (
  name       VARCHAR(64) NOT NULL PRIMARY KEY,
  lock_until TIMESTAMP NOT NULL,
  locked_at  TIMESTAMP NOT NULL,
  locked_by  VARCHAR(255) NOT NULL
);
