package com.wilder0p.lorecleaner.util;

import com.wilder0p.lorecleaner.manager.ConfigManager;

import java.io.File;
import java.util.UUID;

/** Last-logout server from playerdata file mtimes. Fabric files before go-live are ignored. */
public final class LogoutOwner {
    public enum Side { PAPER, FABRIC }

    private LogoutOwner() {}

    public static File paperDat(ConfigManager cfg, UUID uuid) {
        return new File(cfg.getPaperPlayerDataDir(), uuid + ".dat");
    }

    public static File fabricDat(ConfigManager cfg, UUID uuid) {
        return new File(cfg.getFabricPlayerDataDir(), uuid + ".dat");
    }

    public static long mtime(File file) {
        return file != null && file.isFile() ? file.lastModified() : 0L;
    }

    public static long fabricMtime(ConfigManager cfg, UUID uuid) {
        long m = mtime(fabricDat(cfg, uuid));
        if (m > 0 && m < cfg.getFabricGoLiveEpochMs()) {
            return 0L;
        }
        return m;
    }

    public static long paperMtime(ConfigManager cfg, UUID uuid) {
        return mtime(paperDat(cfg, uuid));
    }

    public static long lastSeenMs(ConfigManager cfg, UUID uuid, long bukkitLastPlayed) {
        return Math.max(bukkitLastPlayed, Math.max(paperMtime(cfg, uuid), fabricMtime(cfg, uuid)));
    }

    public static Side owner(ConfigManager cfg, UUID uuid) {
        long fabric = fabricMtime(cfg, uuid);
        long paper = paperMtime(cfg, uuid);
        if (fabric > paper) {
            return Side.FABRIC;
        }
        return Side.PAPER;
    }

    /**
     * N14: wire this-side into ownership gating. True when this server instance
     * should clean this player: the logout owner matches the configured side.
     * When this-side=fabric, Paper-side cleaning is disabled (and vice versa).
     */
    public static boolean isOurs(ConfigManager cfg, UUID uuid) {
        Side want = cfg.isPaperSide() ? Side.PAPER : Side.FABRIC;
        return owner(cfg, uuid) == want;
    }

    public static boolean recentlyTouched(File file, long maxAgeMs) {
        long m = mtime(file);
        return m > 0 && System.currentTimeMillis() - m < maxAgeMs;
    }
}
