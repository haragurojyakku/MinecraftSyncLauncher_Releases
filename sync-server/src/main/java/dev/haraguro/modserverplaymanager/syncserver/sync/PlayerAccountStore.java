package dev.haraguro.modserverplaymanager.syncserver.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.haraguro.modserverplaymanager.shared.model.PlayerProfile;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Persists per-player {@link PlayerProfile} balances at
 * {@code <serverDir>/.mcsync-players.json} — same Gson+Map+synchronized
 * save/load shape as {@link ModCategoryStore}. Emeralds are the in-game
 * representation of this balance, fixed at a 1:1 rate.
 */
public class PlayerAccountStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<UUID, PlayerProfile>>() {
    }.getType();

    private final Path storeFile;
    private volatile Map<UUID, PlayerProfile> profiles;

    public PlayerAccountStore(Path dir) {
        this.storeFile = dir.resolve(".mcsync-players.json");
        this.profiles = load();
    }

    public synchronized PlayerProfile get(UUID uuid) {
        PlayerProfile existing = profiles.get(uuid);
        if (existing != null) {
            return existing;
        }
        PlayerProfile created = new PlayerProfile(uuid, "unknown", 0.0);
        Map<UUID, PlayerProfile> updated = new LinkedHashMap<>(profiles);
        updated.put(uuid, created);
        save(updated);
        return created;
    }

    /** @throws IllegalArgumentException if amount isn't positive. */
    public synchronized PlayerProfile deposit(UUID uuid, double amount) {
        requirePositive(amount);
        PlayerProfile current = get(uuid);
        return put(withBalance(current, current.getBalance() + amount));
    }

    /**
     * @throws IllegalArgumentException if amount isn't positive.
     * @throws InsufficientBalanceException if the account can't cover amount.
     */
    public synchronized PlayerProfile withdraw(UUID uuid, double amount) {
        requirePositive(amount);
        PlayerProfile current = get(uuid);
        if (current.getBalance() < amount) {
            throw new InsufficientBalanceException(uuid, current.getBalance(), amount);
        }
        return put(withBalance(current, current.getBalance() - amount));
    }

    /**
     * @throws IllegalArgumentException if amount isn't positive, or fromUuid equals toUuid.
     * @throws InsufficientBalanceException if fromUuid's account can't cover amount.
     */
    public synchronized void transfer(UUID fromUuid, UUID toUuid, double amount) {
        requirePositive(amount);
        if (fromUuid.equals(toUuid)) {
            throw new IllegalArgumentException("Cannot transfer to the same account: " + fromUuid);
        }
        PlayerProfile from = get(fromUuid);
        if (from.getBalance() < amount) {
            throw new InsufficientBalanceException(fromUuid, from.getBalance(), amount);
        }
        PlayerProfile to = get(toUuid);

        Map<UUID, PlayerProfile> updated = new LinkedHashMap<>(profiles);
        updated.put(fromUuid, withBalance(from, from.getBalance() - amount));
        updated.put(toUuid, withBalance(to, to.getBalance() + amount));
        save(updated);
    }

    private PlayerProfile put(PlayerProfile profile) {
        Map<UUID, PlayerProfile> updated = new LinkedHashMap<>(profiles);
        updated.put(profile.getUuid(), profile);
        save(updated);
        return profile;
    }

    private static PlayerProfile withBalance(PlayerProfile profile, double newBalance) {
        return new PlayerProfile(profile.getUuid(), profile.getLastKnownName(), newBalance);
    }

    private static void requirePositive(double amount) {
        if (!(amount > 0)) {
            throw new IllegalArgumentException("Amount must be positive: " + amount);
        }
    }

    private void save(Map<UUID, PlayerProfile> updated) {
        try {
            Files.createDirectories(storeFile.getParent());
            try (Writer writer = Files.newBufferedWriter(storeFile, StandardCharsets.UTF_8)) {
                GSON.toJson(updated, writer);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to persist " + storeFile, e);
        }
        this.profiles = updated;
    }

    private Map<UUID, PlayerProfile> load() {
        if (!Files.exists(storeFile)) {
            return Map.of();
        }
        try (Reader reader = Files.newBufferedReader(storeFile, StandardCharsets.UTF_8)) {
            Map<UUID, PlayerProfile> loaded = GSON.fromJson(reader, MAP_TYPE);
            return loaded != null ? loaded : Map.of();
        } catch (IOException e) {
            return Map.of();
        }
    }

    /** Thrown by withdraw/transfer when the source account can't cover the requested amount. */
    public static class InsufficientBalanceException extends RuntimeException {
        public InsufficientBalanceException(UUID uuid, double balance, double requested) {
            super("Player " + uuid + " has balance " + balance + ", requested " + requested);
        }
    }
}
