package dev.haraguro.modserverplaymanager.servermanager.process;

import dev.haraguro.modserverplaymanager.shared.model.ConnectionAttempt;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the vanilla server's own console lines into {@link ConnectionAttempt}
 * events — no RCON polling involved, just pattern-matching the same lines
 * {@link ServerOutputPump} already streams through
 * {@link ServerProcessSupervisor#onOutputLine}. Vanilla resolves a player's
 * uuid (offline-derived or Mojang-looked-up) BEFORE the whitelist/ban check
 * runs, logging "UUID of player &lt;name&gt; is &lt;uuid&gt;" either way;
 * that uuid is cached here by name until the very next join-or-reject line
 * for that name resolves it into a full event. This log wording has been
 * stable across a wide range of vanilla/Fabric versions but isn't
 * contractually guaranteed by Mojang — if a future version rewords these,
 * only the patterns below need updating.
 */
public class ConnectionLogParser {

    private static final Pattern UUID_LINE = Pattern.compile(
            "UUID of player (\\S+) is ([0-9a-fA-F-]{36})");
    private static final Pattern JOINED_LINE = Pattern.compile(
            "(\\S+) joined the game");
    // Covers every login-time rejection vanilla logs this way (not
    // whitelisted, banned, outdated client, server full, ...) — name first,
    // free-form reason after the colon.
    private static final Pattern DISCONNECTING_LINE = Pattern.compile(
            "Disconnecting (\\S+)(?: \\([^)]*\\))?: (.+)");

    // Bounds the wait between "UUID of player X is ..." and the join/reject
    // line that resolves it — capped defensively in case a client vanishes
    // mid-handshake and never frees its entry.
    private static final int MAX_PENDING_UUIDS = 50;

    private final Map<String, String> pendingUuidByName = new LinkedHashMap<>();

    /** Feed console lines to this one at a time, in order; returns an event when a line completes one. */
    public synchronized Optional<ConnectionAttempt> onLine(String line) {
        Matcher uuidMatcher = UUID_LINE.matcher(line);
        if (uuidMatcher.find()) {
            if (pendingUuidByName.size() >= MAX_PENDING_UUIDS) {
                pendingUuidByName.remove(pendingUuidByName.keySet().iterator().next());
            }
            pendingUuidByName.put(uuidMatcher.group(1), uuidMatcher.group(2));
            return Optional.empty();
        }

        Matcher joinedMatcher = JOINED_LINE.matcher(line);
        if (joinedMatcher.find()) {
            String name = joinedMatcher.group(1);
            String uuid = pendingUuidByName.remove(name);
            return Optional.of(new ConnectionAttempt(name, uuid, true, null, System.currentTimeMillis()));
        }

        Matcher disconnectMatcher = DISCONNECTING_LINE.matcher(line);
        if (disconnectMatcher.find()) {
            String name = disconnectMatcher.group(1);
            String reason = disconnectMatcher.group(2);
            String uuid = pendingUuidByName.remove(name);
            return Optional.of(new ConnectionAttempt(name, uuid, false, reason, System.currentTimeMillis()));
        }

        return Optional.empty();
    }
}
