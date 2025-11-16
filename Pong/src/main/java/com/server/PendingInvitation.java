package com.server;

public class PendingInvitation {
    private String id;
    private String fromPlayer;
    private String toPlayer;
    private long createdAt;
    private static final long TIMEOUT_MS = 30000; // 30 segundos

    public PendingInvitation(String id, String fromPlayer, String toPlayer, long createdAt) {
        this.id = id;
        this.fromPlayer = fromPlayer;
        this.toPlayer = toPlayer;
        this.createdAt = createdAt;
    }

    // Getters
    public String getId() { return id; }
    public String getFromPlayer() { return fromPlayer; }
    public String getToPlayer() { return toPlayer; }
    public long getCreatedAt() { return createdAt; }

    public boolean isExpired() {
        return System.currentTimeMillis() - createdAt > TIMEOUT_MS;
    }
}