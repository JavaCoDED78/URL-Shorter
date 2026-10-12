/**
 * Atomic Redis Lua scripts (counter drain + rate limiter). Each script runs atomically inside
 * Redis, which is the guarantee that keeps the read-and-reset drain (Tasks 8.3) and the INCR+EXPIRE
 * limiter probe (Task 20.4) free of interleaving windows between the two round-trips.
 */
@org.jspecify.annotations.NullMarked
package com.javaded78.urlshorter.infra.redis.script;
