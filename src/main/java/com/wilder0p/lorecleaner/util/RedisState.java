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
import java.util.UUID;
import java.util.logging.Logger;

/** Shared cleaned/scanned/pending-login keys plus BCH location for cross-server online. */
public final class RedisState {

    /** N12: connection settings; defaults preserve the previous hardcoded values. */
    public static final class Options {
        public String sentinelMaster = "azpbmd";
        public java.util.List<String> sentinels = java.util.List.of(
                "10.0.0.1:26379", "10.0.0.2:26379", "10.0.0.3:26379");
        public String fallbackHost = "10.0.0.3";
        public int fallbackPort = 6379;
        public String passwordFile = "/mnt/pool/skygate/redis.pass";
        /** N3: when true, an unreachable Redis means "treat as online" (skip the player). */
        public boolean failClosed = true;
    }

    private final Logger logger;
    private final Options opts;
    private UnifiedJedis jedis;

    public RedisState(Logger logger) {
        this(logger, new Options());
    }

    public RedisState(Logger logger, Options opts) {
        this.logger = logger;
        this.opts = opts != null ? opts : new Options();
    }

    public void start() {
        String password = "";
        try {
            Path pf = Path.of(opts.passwordFile);
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
        java.util.Set<HostAndPort> sentinelSet = new java.util.HashSet<>();
        for (String s : opts.sentinels) {
            try {
                int colon = s.lastIndexOf(':');
                sentinelSet.add(new HostAndPort(s.substring(0, colon),
                        Integer.parseInt(s.substring(colon + 1))));
            } catch (Exception e) {
                logger.warning("LoreCleaner ignoring malformed redis sentinel entry: " + s);
            }
        }
        UnifiedJedis sentinelJedis = null;
        try {
            sentinelJedis = new JedisSentineled(opts.sentinelMaster, cfg, sentinelSet, sentinelCfg);
            sentinelJedis.ping();
            jedis = sentinelJedis;
            logger.info("LoreCleaner Redis via Sentinel master=" + opts.sentinelMaster);
        } catch (Exception e) {
            if (sentinelJedis != null) {
                try { sentinelJedis.close(); } catch (Exception ignored) {}
            }
            logger.warning("LoreCleaner Sentinel failed (" + e.getMessage() + "), falling back to "
                    + opts.fallbackHost + ":" + opts.fallbackPort);
            // N2: never let a Redis outage kill onEnable — degrade instead.
            UnifiedJedis direct = null;
            try {
                direct = new JedisPooled(new HostAndPort(opts.fallbackHost, opts.fallbackPort), cfg);
                direct.ping();
                jedis = direct;
                logger.info("LoreCleaner Redis via direct " + opts.fallbackHost + ":" + opts.fallbackPort);
            } catch (Exception e2) {
                if (direct != null) {
                    try { direct.close(); } catch (Exception ignored) {}
                }
                jedis = null;
                logger.severe("LoreCleaner Redis unavailable (" + e2.getMessage()
                        + ") — running in degraded single-server mode");
            }
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

    /**
     * True if BackChatHelper says they are on some network server.
     * N3: fail closed — when the online status cannot be determined, treat the
     * player as online so they are skipped instead of wrongly cleaned.
     */
    public boolean isNetworkOnline(UUID uuid) {
        if (uuid == null) {
            return opts.failClosed;
        }
        if (jedis == null) {
            return opts.failClosed;
        }
        try {
            String loc = jedis.get("bch:loc:" + uuid);
            return loc != null && !loc.isBlank();
        } catch (Exception e) {
            return opts.failClosed;
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
