package dev.haraguro.modserverplaymanager.shared.model;

/**
 * One player join attempt seen in the supervised server's console output
 * (see server-manager's ConnectionLogParser), as listed by
 * /api/manager/connection-attempts. Exists mainly so the server owner can
 * grab the exact name/uuid of a friend who just got bounced for not being
 * whitelisted, without digging through the raw console log themselves.
 * Recent-only (in-memory, resets on server-manager restart) — not a
 * permanent audit trail.
 */
public class ConnectionAttempt {

    private String name;
    private String uuid;
    private boolean accepted;
    /** Null when accepted=true; the disconnect reason (e.g. "not whitelisted") otherwise. */
    private String reason;
    private long timestampMillis;

    public ConnectionAttempt() {
    }

    public ConnectionAttempt(String name, String uuid, boolean accepted, String reason, long timestampMillis) {
        this.name = name;
        this.uuid = uuid;
        this.accepted = accepted;
        this.reason = reason;
        this.timestampMillis = timestampMillis;
    }

    public String getName() {
        return name;
    }

    public String getUuid() {
        return uuid;
    }

    public boolean isAccepted() {
        return accepted;
    }

    public String getReason() {
        return reason;
    }

    public long getTimestampMillis() {
        return timestampMillis;
    }
}
