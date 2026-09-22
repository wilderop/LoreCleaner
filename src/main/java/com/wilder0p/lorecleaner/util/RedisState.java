package com.wilder0p.lorecleaner.util;

import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.JedisSentineled;
import redis.clients.jedis.UnifiedJedis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/** Shared cleaned/scanned/pending-login keys plus BCH location for cross-server online. */
public final class RedisState {
    private static final String SENTINEL_MASTER = "azpbmd";
    private static final Set<HostAndPort> SENTINELS = Set.of(
            new HostAndPort("10.0.0.1", 26379),
            new HostAndPort("10.0.0.2", 26379),
            new HostAndPort("10.0.0.3", 26379));

    private final Logger logger;
    private UnifiedJedis jedis;

    public RedisState(Logger logger) {
        this.logger = logger;
    }

    public void start() {
        String password = "";
        try {
            Path pf = Path.of("/mnt/pool/skygate/redis.pass");
            if (Files.isRegularFile(pf)) {
                password = Files.readString(pf).trim();
            }
        } catch (Exception ignored) {
        }
        DefaultJedisClientConfig.Builder b = DefaultJedisClientConfig.builder()
                .socketTimeoutMillis(3000)
                .connectionTimeoutMillis(3000);
        if (!password.isBlank()) {
            b.password(password);
        }
        JedisClientConfig cfg = b.build();
        JedisClientConfig sentinelCfg = DefaultJedisClientConfig.builder()
                .socketTimeoutMillis(3000)
                .connectionTimeoutMillis(3000)
                .build();
        try {
            jedis = new JedisSentineled(SENTINEL_MASTER, cfg, SENTINELS, sentinelCfg);
            jedis.ping();
            logger.info("LoreCleaner Redis via Sentinel master=" + SENTINEL_MASTER);
        } catch (Exception e) {
            logger.warning("LoreCleaner Sentinel failed (" + e.getMessage() + "), falling back to 10.0.0.3:6379");
            jedis = new JedisPooled(new HostAndPort("10.0.0.3", 6379), cfg);
            jedis.ping();
        }
    }

    public void stop() {
        if (jedis != null) {
            try {
                jedis.close();
            } catch (Exception ignored) {
            }
            jedis = null;
        }
    }

    public boolean ready() {
        return jedis != null;
    }

    /** True if BackChatHelper says they are on some network server. */
    public boolean isNetworkOnline(UUID uuid) {
        if (jedis == null || uuid == null) {
            return false;
        }
        try {
            String loc = jedis.get("bch:loc:" + uuid);
            return loc != null && !loc.isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    public Long getScanned(UUID uuid) {
        return getLong("lorecleaner:scanned:" + uuid);
    }

    public void setScanned(UUID uuid, long lastPlayed) {
        set("lorecleaner:scanned:" + uuid, Long.toString(lastPlayed));
    }

    public Instant getCleaned(UUID uuid) {
        Long ms = getLong("lorecleaner:cleaned:" + uuid);
        return ms == null ? null : Instant.ofEpochMilli(ms);
    }

    public void setCleaned(UUID uuid) {
        long now = System.currentTimeMillis();
        set("lorecleaner:cleaned:" + uuid, Long.toString(now));
        set("lorecleaner:pendingmsg:" + uuid, "1");
    }

    public boolean hasPendingMessage(UUID uuid) {
        if (jedis == null) {
            return false;
        }
        try {
            return "1".equals(jedis.get("lorecleaner:pendingmsg:" + uuid));
        } catch (Exception e) {
            return false;
        }
    }

    public void clearPendingMessage(UUID uuid) {
        del("lorecleaner:pendingmsg:" + uuid);
    }

    private Long getLong(String key) {
        if (jedis == null) {
            return null;
        }
        try {
            String v = jedis.get(key);
            if (v == null || v.isBlank()) {
                return null;
            }
            return Long.parseLong(v);
        } catch (Exception e) {
            return null;
        }
    }

    private void set(String key, String value) {
        if (jedis == null) {
            return;
        }
        try {
            jedis.set(key, value);
        } catch (Exception e) {
            logger.warning("Redis SET " + key + " failed: " + e.getMessage());
        }
    }

    private void del(String key) {
        if (jedis == null) {
            return;
        }
        try {
            jedis.del(key);
        } catch (Exception ignored) {
        }
    }
}
