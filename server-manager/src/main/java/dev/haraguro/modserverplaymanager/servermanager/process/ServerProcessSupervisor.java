package dev.haraguro.modserverplaymanager.servermanager.process;

import dev.haraguro.modserverplaymanager.servermanager.rcon.RconClient;
import dev.haraguro.modserverplaymanager.shared.model.ConnectionAttempt;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Owns the actual Fabric server child process: builds the java command
 * (mirroring server/start.bat|sh, plus -XX:+ExitOnOutOfMemoryError so an
 * OOM produces a detectable exit instead of a hung JVM), captures its
 * output instead of inheriting the console, and tells an unexpected crash
 * apart from an intentional stop via a flag set BEFORE the stop is
 * requested — not by inspecting the exit code, which isn't a reliable
 * cross-platform signal here.
 *
 * On Windows in particular, Process.destroy()/destroyForcibly() do not
 * trigger a graceful Minecraft shutdown the way RCON "stop" does, so stop()
 * always tries RCON first and only escalates to destroyForcibly() as a
 * timeout-based last resort.
 *
 * A supervisor only knows about a child it itself spawned in this JVM's
 * lifetime — if server-manager is restarted while the Fabric process it
 * previously launched is still alive (e.g. the manager was killed/crashed
 * without going through stop(), or was simply relaunched), the new
 * supervisor's `process` field starts out null even though a real server is
 * running. Blindly calling start() in that state spawns a second JVM that
 * immediately fails on the world's session.lock, and since that failure
 * looks like a crash, the crash-loop guard would auto-restart it over and
 * over — all while the original, perfectly healthy server sits there
 * un-tracked. start() guards against this by probing RCON before spawning:
 * if something already answers on rcon.port, that's an existing live server,
 * and it's adopted (state RUNNING, no owned Process/pid) instead of
 * duplicated. A lightweight watchdog periodically re-probes RCON while a
 * process is adopted so state falls back to STOPPED if that external
 * instance later goes away on its own.
 */
public class ServerProcessSupervisor {

    private final Path serverDir;
    private final String minMemory;
    private final String maxMemory;
    private final int rconPort;
    private final String rconPassword;
    private final Duration rconTimeout;
    private final int maxRestartsPerWindow;
    private final Duration crashWindow;
    private final Path logFile;
    private final Runnable onBootComplete;

    private final Deque<Long> recentCrashTimestamps = new ArrayDeque<>();
    private final ConnectionLogParser connectionLogParser = new ConnectionLogParser();
    private final Deque<ConnectionAttempt> recentConnectionAttempts = new ArrayDeque<>();
    private static final int MAX_RECENT_CONNECTION_ATTEMPTS = 50;
    // "<name> left the game" — a normal disconnect, distinct from ConnectionLogParser's
    // "Disconnecting <name>: <reason>" (a pre-join rejection). Only used to fire
    // onPlayerLeft below, so it lives here rather than in ConnectionLogParser's own
    // ConnectionAttempt-shaped contract.
    private static final Pattern LEFT_LINE = Pattern.compile("(\\S+) left the game");
    private volatile Runnable onPlayerJoined = () -> {
    };
    private volatile Runnable onPlayerLeft = () -> {
    };

    // Rolling window of the child process's own console output — a crash's
    // actual cause (exception + stack trace) is always at/near the tail of
    // this by the time handleExit() fires, regardless of how much mod-load
    // chatter came before it. Snapshotted into lastCrashReason on an
    // unexpected exit so the launcher GUI can show *why*, not just *that*.
    private static final int MAX_RECENT_OUTPUT_LINES = 40;
    private final Deque<String> recentOutputLines = new ArrayDeque<>();
    private volatile String lastCrashReason;

    private volatile ProcessState state = ProcessState.STOPPED;
    private volatile boolean stopRequested = false;
    private Process process;
    private ServerOutputPump outputPump;
    private long startedAtMillis;
    private int crashRestartCount = 0;

    // Set when start() finds a live server it didn't spawn (see class doc) — RCON-backed
    // operations (list players, say, whitelist reload, stop) work fine against it, but
    // there's no Process/pid to report or forcibly kill.
    private volatile boolean adoptedExternalProcess = false;
    private static final Duration ADOPTED_LIVENESS_POLL_INTERVAL = Duration.ofSeconds(30);
    private final ScheduledExecutorService adoptedWatchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "adopted-server-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    public ServerProcessSupervisor(Path serverDir, String minMemory, String maxMemory,
                                    int rconPort, String rconPassword, Duration rconTimeout,
                                    int maxRestartsPerWindow, Duration crashWindow, Path logFile,
                                    Runnable onBootComplete) {
        this.serverDir = serverDir;
        this.minMemory = minMemory;
        this.maxMemory = maxMemory;
        this.rconPort = rconPort;
        this.rconPassword = rconPassword;
        this.rconTimeout = rconTimeout;
        this.maxRestartsPerWindow = maxRestartsPerWindow;
        this.crashWindow = crashWindow;
        this.logFile = logFile;
        this.onBootComplete = onBootComplete;

        adoptedWatchdog.scheduleWithFixedDelay(this::checkAdoptedLiveness,
                ADOPTED_LIVENESS_POLL_INTERVAL.toSeconds(), ADOPTED_LIVENESS_POLL_INTERVAL.toSeconds(),
                TimeUnit.SECONDS);
    }

    /** Stops the background adopted-process watchdog. Call once on application shutdown. */
    public void shutdown() {
        adoptedWatchdog.shutdownNow();
    }

    /** Fired when a player's connection completes (see ConnectionLogParser's "joined the game" line) — for backup triggers. */
    public void setOnPlayerJoined(Runnable handler) {
        this.onPlayerJoined = handler;
    }

    /** Fired on a normal disconnect ("&lt;name&gt; left the game") — for backup triggers. */
    public void setOnPlayerLeft(Runnable handler) {
        this.onPlayerLeft = handler;
    }

    public synchronized void start() throws IOException {
        if (process != null && process.isAlive()) {
            throw new IllegalStateException("server process is already running");
        }
        if (isRconReachable()) {
            // Someone/something is already listening on rcon.port — almost certainly a
            // server this (fresh) supervisor instance didn't spawn, left over from before
            // a server-manager restart. Adopt it rather than racing it for world/session.lock.
            adoptedExternalProcess = true;
            state = ProcessState.RUNNING;
            startedAtMillis = System.currentTimeMillis();
            System.out.println("server-manager: detected an already-running server via RCON that this "
                    + "instance did not start (likely left over from a previous server-manager session) — "
                    + "adopting it instead of starting a duplicate. pid/true uptime are unavailable until "
                    + "it's stopped and started again through this manager.");
            if (onBootComplete != null) {
                onBootComplete.run();
            }
            return;
        }

        adoptedExternalProcess = false;
        state = ProcessState.STARTING;
        stopRequested = false;

        ProcessBuilder builder = new ProcessBuilder(buildCommand());
        builder.directory(serverDir.toFile());
        process = builder.start();
        startedAtMillis = System.currentTimeMillis();

        outputPump = new ServerOutputPump(process, logFile, this::onOutputLine);
        outputPump.start();
        process.onExit().thenAccept(this::handleExit);

        System.out.println("server-manager: server process starting (pid " + process.pid() + ")");
    }

    /** Requests a graceful stop via RCON, falling back to a forced kill if it doesn't exit within grace. */
    public void stop(Duration grace) {
        Process current = process;
        boolean ownedAlive = current != null && current.isAlive();
        if (!ownedAlive) {
            if (adoptedExternalProcess && state == ProcessState.RUNNING) {
                stopAdopted(grace);
            }
            return;
        }
        stopRequested = true;
        state = ProcessState.STOPPING;

        if (!tryRconStop()) {
            System.out.println("server-manager: RCON stop failed; will wait out the grace period then force-kill");
        }

        try {
            boolean exited = current.waitFor(grace.toSeconds(), TimeUnit.SECONDS);
            if (!exited) {
                System.out.println("server-manager: server did not exit within grace period, forcing shutdown");
                current.destroyForcibly();
                boolean killed = current.waitFor(10, TimeUnit.SECONDS);
                if (!killed) {
                    System.out.println("server-manager: WARNING - forced kill did not confirm exit within 10s; "
                            + "the process may still be shutting down in the background. A subsequent start() "
                            + "will detect it via RCON and adopt it rather than racing it for the world lock.");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public synchronized void restart(Duration grace) throws IOException {
        stop(grace);
        start();
    }

    /**
     * Best-effort stop for a server we adopted rather than spawned (see start()) — there's no
     * owned Process/pid to force-kill, so this is RCON "stop" followed by polling RCON
     * reachability until the grace period elapses. If it's still up after that, it's left
     * running and logged; a later start() will simply re-adopt it.
     */
    private void stopAdopted(Duration grace) {
        state = ProcessState.STOPPING;
        if (!tryRconStop()) {
            System.out.println("server-manager: RCON stop failed for adopted server; nothing more this "
                    + "instance can do — it has no pid to force-kill.");
        }

        long deadline = System.currentTimeMillis() + grace.toMillis();
        boolean stillReachable = true;
        while (System.currentTimeMillis() < deadline) {
            stillReachable = isRconReachable();
            if (!stillReachable) {
                break;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stillReachable = isRconReachable();
                break;
            }
        }

        if (stillReachable) {
            System.out.println("server-manager: adopted server did not stop within the grace period and "
                    + "cannot be force-killed without a pid — leaving it running.");
            state = ProcessState.RUNNING;
        } else {
            adoptedExternalProcess = false;
            state = ProcessState.STOPPED;
        }
    }

    public ProcessState getState() {
        return state;
    }

    public long getPid() {
        Process current = process;
        return (current != null && current.isAlive()) ? current.pid() : -1;
    }

    public long getUptimeMillis() {
        if (state != ProcessState.RUNNING && state != ProcessState.STARTING) {
            return 0;
        }
        return System.currentTimeMillis() - startedAtMillis;
    }

    public int getCrashRestartCount() {
        return crashRestartCount;
    }

    /** The last MAX_RECENT_OUTPUT_LINES lines of console output at the moment of the most recent unexpected exit, or null if none yet. */
    public String getLastCrashReason() {
        return lastCrashReason;
    }

    public boolean isAlive() {
        Process current = process;
        if (current != null && current.isAlive()) {
            return true;
        }
        return adoptedExternalProcess && state == ProcessState.RUNNING;
    }

    /** Empty immediately if not RUNNING — avoids a multi-second RCON timeout against a stopped/starting server. */
    public List<String> listOnlinePlayers() {
        if (state != ProcessState.RUNNING) {
            return List.of();
        }
        try (RconClient rcon = RconClient.connectAndAuthenticate(rconPort, rconPassword, rconTimeout)) {
            return parsePlayerList(rcon.execute("list"));
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Broadcasts a message to all connected players via RCON `say`. Best-effort — failures are logged, not thrown. */
    public void broadcastSay(String message) {
        try (RconClient rcon = RconClient.connectAndAuthenticate(rconPort, rconPassword, rconTimeout)) {
            rcon.execute("say " + message);
        } catch (IOException e) {
            System.out.println("server-manager: failed to broadcast message: " + e.getMessage());
        }
    }

    /**
     * Tells an already-running server to re-read server/whitelist.json via RCON's
     * own `whitelist reload`. The caller (WhitelistService) always writes that file
     * itself using the deterministic offline uuid — every server this project manages
     * runs offline-mode (see launcher's OfflineAuth) — so there's never a Mojang
     * lookup to defer to here.
     *
     * Deliberately NOT `whitelist add <name>`/`whitelist remove <name>`: those vanilla
     * commands resolve the player's uuid from the server's own profile cache
     * (usercache.json) by name, which can hold a stale uuid from before this project's
     * offline-mode switch (e.g. a real Mojang uuid from an earlier online-mode session
     * under the same name). That stale uuid would get written into whitelist.json
     * instead of the offline one the client actually presents, silently locking the
     * player back out right after they were "added". Reload avoids touching identity
     * resolution at all — the file already has the right uuid.
     *
     * Returns false (no-op) if the server isn't running right now; the file edit
     * still applies on next boot.
     */
    public boolean reloadWhitelistIfRunning() {
        if (state != ProcessState.RUNNING) {
            return false;
        }
        try (RconClient rcon = RconClient.connectAndAuthenticate(rconPort, rconPassword, rconTimeout)) {
            rcon.execute("whitelist reload");
            return true;
        } catch (IOException e) {
            System.out.println("server-manager: failed to reload whitelist via RCON: " + e.getMessage());
            return false;
        }
    }

    /** Parses vanilla's "There are N of a max of M players online: a, b, c" (empty after the colon if none online). */
    private static List<String> parsePlayerList(String response) {
        int colon = response.indexOf(':');
        if (colon < 0) {
            return List.of();
        }
        String namesPart = response.substring(colon + 1).trim();
        if (namesPart.isEmpty()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (String name : namesPart.split(",")) {
            String trimmed = name.trim();
            if (!trimmed.isEmpty()) {
                names.add(trimmed);
            }
        }
        return names;
    }

    private List<String> buildCommand() {
        return List.of(
                "java",
                "-Xms" + minMemory,
                "-Xmx" + maxMemory,
                "-XX:+ExitOnOutOfMemoryError",
                "-jar", "fabric-server-launch.jar",
                "nogui"
        );
    }

    private void onOutputLine(String line) {
        if (state == ProcessState.STARTING && line.contains("Done (")) {
            state = ProcessState.RUNNING;
            System.out.println("server-manager: server reported boot complete");
            if (onBootComplete != null) {
                onBootComplete.run();
            }
        }
        connectionLogParser.onLine(line).ifPresent(attempt -> {
            recordConnectionAttempt(attempt);
            if (attempt.isAccepted()) {
                onPlayerJoined.run();
            }
        });
        Matcher leftMatcher = LEFT_LINE.matcher(line);
        if (leftMatcher.find()) {
            onPlayerLeft.run();
        }
        recordOutputLine(line);
    }

    private synchronized void recordOutputLine(String line) {
        recentOutputLines.addLast(line);
        while (recentOutputLines.size() > MAX_RECENT_OUTPUT_LINES) {
            recentOutputLines.removeFirst();
        }
    }

    private synchronized void recordConnectionAttempt(ConnectionAttempt attempt) {
        recentConnectionAttempts.addLast(attempt);
        while (recentConnectionAttempts.size() > MAX_RECENT_CONNECTION_ATTEMPTS) {
            recentConnectionAttempts.removeFirst();
        }
    }

    /** Most recent join attempts first — see ConnectionLogParser for how these are detected. */
    public synchronized List<ConnectionAttempt> recentConnectionAttempts() {
        List<ConnectionAttempt> reversed = new ArrayList<>(recentConnectionAttempts);
        Collections.reverse(reversed);
        return reversed;
    }

    private boolean tryRconStop() {
        try (RconClient rcon = RconClient.connectAndAuthenticate(rconPort, rconPassword, rconTimeout)) {
            rcon.execute("stop");
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Pure liveness probe (connect + auth, no command) — used to detect a server this instance didn't spawn. */
    private boolean isRconReachable() {
        try (RconClient rcon = RconClient.connectAndAuthenticate(rconPort, rconPassword, rconTimeout)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Runs on a background timer; only acts while a server is adopted, so it's a no-op the rest of the time. */
    private synchronized void checkAdoptedLiveness() {
        if (!adoptedExternalProcess || state != ProcessState.RUNNING) {
            return;
        }
        if (!isRconReachable()) {
            System.out.println("server-manager: adopted external server is no longer reachable via RCON — "
                    + "marking as stopped.");
            adoptedExternalProcess = false;
            state = ProcessState.STOPPED;
        }
    }

    private synchronized void handleExit(Process exitedProcess) {
        if (exitedProcess != process) {
            // Stale callback for a process instance restart() has already superseded —
            // e.g. this fired only after being blocked on the monitor restart() held
            // across its own stop()+start(), by which point start() had already reset
            // stopRequested for the NEW process. Acting on it here would misreport a
            // clean restart as an unexpected crash. The new process's own callback
            // (registered by that start()) is what actually tracks its exit.
            return;
        }

        try {
            outputPump.awaitCompletion(Duration.ofSeconds(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (stopRequested) {
            state = ProcessState.STOPPED;
            stopRequested = false;
            System.out.println("server-manager: server stopped (intentional)");
            return;
        }

        state = ProcessState.CRASHED;
        lastCrashReason = String.join(System.lineSeparator(), recentOutputLines);
        recordCrash();
        System.out.println("server-manager: server exited unexpectedly (exit code "
                + exitedProcess.exitValue() + ")");

        if (withinCrashLoopGuard()) {
            System.out.println("server-manager: auto-restarting (" + recentCrashTimestamps.size()
                    + "/" + maxRestartsPerWindow + " restarts in the last " + crashWindow.toMinutes() + "m)");
            try {
                start();
            } catch (IOException e) {
                System.out.println("server-manager: auto-restart failed: " + e.getMessage());
            }
        } else {
            System.out.println("server-manager: crash-loop guard tripped ("
                    + maxRestartsPerWindow + " restarts within " + crashWindow.toMinutes()
                    + "m) — giving up. Use POST /api/manager/restart once the underlying issue is fixed.");
        }
    }

    private synchronized void recordCrash() {
        long now = System.currentTimeMillis();
        recentCrashTimestamps.addLast(now);
        long windowStart = now - crashWindow.toMillis();
        while (!recentCrashTimestamps.isEmpty() && recentCrashTimestamps.peekFirst() < windowStart) {
            recentCrashTimestamps.removeFirst();
        }
        crashRestartCount++;
    }

    private synchronized boolean withinCrashLoopGuard() {
        return recentCrashTimestamps.size() <= maxRestartsPerWindow;
    }
}
