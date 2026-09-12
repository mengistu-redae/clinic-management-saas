package com.clinicops.appointment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;

/**
 * Short-lived, per-slot Redis lock so two callers (a patient in the portal
 * and front-desk staff at the counter, say) can never both confirm the same
 * slot - line-for-line port of the reference bus-ticketing-saas project's
 * SeatLockService, keyed by slot id instead of seat id.
 *
 * Each lock's value is a random token unique to the request holding it, so
 * release only ever deletes a lock this request actually owns - without
 * that check, a slow request could delete a lock that expired and was
 * already re-acquired by someone else.
 */
@Service
public class SlotLockService {

    private static final String KEY_PREFIX = "slot-lock:";

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
        "if redis.call('get', KEYS[1]) == ARGV[1] then " +
        "  return redis.call('del', KEYS[1]) " +
        "else " +
        "  return 0 " +
        "end",
        Long.class
    );

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;

    public SlotLockService(
            StringRedisTemplate redisTemplate,
            @Value("${clinicops.slot-lock.ttl-seconds}") long ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    /** Returns true if the lock was acquired, false if another request already holds it. */
    public boolean tryAcquire(String slotId, String lockToken) {
        Boolean acquired = redisTemplate.opsForValue()
            .setIfAbsent(KEY_PREFIX + slotId, lockToken, ttl);
        return Boolean.TRUE.equals(acquired);
    }

    /** Releases the lock only if this request's token is still the one holding it. */
    public void release(String slotId, String lockToken) {
        redisTemplate.execute(
            RELEASE_SCRIPT,
            Collections.singletonList(KEY_PREFIX + slotId),
            lockToken
        );
    }
}
