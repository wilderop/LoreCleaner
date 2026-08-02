package com.wilder0p.lorecleaner.model;

import java.util.UUID;

/** Offline player eligible for scan/clean, ordered by lastPlayed. */
public final class OfflinePlayerCandidate {
    public final UUID uuid;
    public final long lastPlayed;

    public OfflinePlayerCandidate(UUID uuid, long lastPlayed) {
        this.uuid = uuid;
        this.lastPlayed = lastPlayed;
    }
}
