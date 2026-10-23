package com.seekora.security;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small in-memory fixed-window counter. It protects the paid AI provider and the login endpoint from abuse by a
 * single instance. It is deliberately simple: counters are per process, so with several instances the effective
 * limit is multiplied (Render's single-instance web service is the supported deployment).
 */
public class RateLimiter {
    private record Window(long startMillis, int count) { }

    private final int maxHits;
    private final long windowMillis;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimiter(int maxHits, Duration window) {
        this.maxHits = maxHits;
        this.windowMillis = window.toMillis();
    }

    /** Records one hit and returns true while the key is still within its limit. */
    public boolean tryHit(String key) {
        long now = System.currentTimeMillis();
        if (windows.size() > 10_000) evictExpired(now);
        Window updated = windows.compute(key, (k, w) ->
                w == null || now - w.startMillis >= windowMillis ? new Window(now, 1) : new Window(w.startMillis, w.count + 1));
        return updated.count <= maxHits;
    }

    /** True when the key has already used up its limit in the current window (does not record a hit). */
    public boolean isBlocked(String key) {
        Window w = windows.get(key);
        return w != null && System.currentTimeMillis() - w.startMillis < windowMillis && w.count >= maxHits;
    }

    public void reset(String key) { windows.remove(key); }

    private void evictExpired(long now) {
        windows.entrySet().removeIf(e -> now - e.getValue().startMillis >= windowMillis);
    }
}
