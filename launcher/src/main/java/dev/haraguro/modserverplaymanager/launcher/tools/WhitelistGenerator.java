package dev.haraguro.modserverplaymanager.launcher.tools;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.haraguro.modserverplaymanager.launcher.launch.AuthSession;
import dev.haraguro.modserverplaymanager.launcher.launch.OfflineAuth;

/**
 * Prints server/whitelist.json entries for offline-mode usernames.
 *
 * With online-mode=false (this project's default — see OfflineAuth), the
 * server never asks Mojang who's really behind a username, so anyone can
 * claim any name unless the server's whitelist is populated. Offline UUIDs
 * are deterministic (UUID.nameUUIDFromBytes("OfflinePlayer:" + name)), so
 * this just needs the agreed-upon usernames, not real accounts.
 *
 * Usage: ./gradlew :launcher:generateWhitelist -PwhitelistNames=alice,bob
 * Paste the output into server/whitelist.json and set white-list=true in
 * server/server.properties.
 */
public class WhitelistGenerator {

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: ./gradlew :launcher:generateWhitelist -PwhitelistNames=alice,bob,carol");
            System.exit(1);
        }

        JsonArray entries = new JsonArray();
        for (String name : args) {
            AuthSession session = OfflineAuth.session(name);
            JsonObject entry = new JsonObject();
            entry.addProperty("uuid", session.uuid().toString());
            entry.addProperty("name", session.playerName());
            entries.add(entry);
        }

        System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(entries));
    }
}
