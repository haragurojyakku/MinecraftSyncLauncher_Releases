package dev.haraguro.modserverplaymanager.shared.model;

import java.util.UUID;

/**
 * Player data owned by sync-server. The Fabric mod reads/writes this over
 * HTTP instead of keeping its own datastore, so all game servers share one
 * source of truth.
 */
public class PlayerProfile {

    private UUID uuid;
    private String lastKnownName;
    private double balance;

    public PlayerProfile() {
    }

    public PlayerProfile(UUID uuid, String lastKnownName, double balance) {
        this.uuid = uuid;
        this.lastKnownName = lastKnownName;
        this.balance = balance;
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getLastKnownName() {
        return lastKnownName;
    }

    public double getBalance() {
        return balance;
    }
}
