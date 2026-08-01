package dev.haraguro.modserverplaymanager.servermanager.process;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Pumps a child process's stdout/stderr to a log file and this process's
 * own console, on daemon threads. The child is deliberately NOT launched
 * with ProcessBuilder.inheritIO() (see ServerProcessSupervisor) so
 * server-manager can capture and log this output instead of it going
 * straight to an inherited console — stdin stays reserved for
 * server-manager's own use (RCON covers server control instead).
 *
 * lineListener is a hook for the supervisor to watch for the vanilla
 * "Done (" boot-complete marker; the same hook a future TPS/crash-signature
 * scanner would reuse.
 */
public class ServerOutputPump {

    private final Process process;
    private final Path logFile;
    private final Consumer<String> lineListener;
    private BufferedWriter writer;
    private Thread stdoutThread;
    private Thread stderrThread;

    public ServerOutputPump(Process process, Path logFile, Consumer<String> lineListener) {
        this.process = process;
        this.logFile = logFile;
        this.lineListener = lineListener;
    }

    public void start() throws IOException {
        Files.createDirectories(logFile.getParent());
        writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        stdoutThread = pump(process.getInputStream(), "server");
        stderrThread = pump(process.getErrorStream(), "server-err");
    }

    private Thread pump(InputStream stream, String consolePrefix) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("[" + consolePrefix + "] " + line);
                    writeLine(line);
                    if (lineListener != null) {
                        lineListener.accept(line);
                    }
                }
            } catch (IOException e) {
                // Stream closed because the child process exited — expected, not an error.
            }
        }, "server-manager-output-" + consolePrefix);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void writeLine(String line) {
        synchronized (writer) {
            try {
                writer.write(line);
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                // Best-effort logging; losing a log line shouldn't take the supervisor down.
            }
        }
    }

    /** Blocks until both pump threads finish (the child's streams closed) or the timeout elapses. */
    public void awaitCompletion(Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        if (stdoutThread != null) {
            stdoutThread.join(Math.max(0, deadline - System.currentTimeMillis()));
        }
        if (stderrThread != null) {
            stderrThread.join(Math.max(0, deadline - System.currentTimeMillis()));
        }
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException e) {
                // ignore — best-effort logging
            }
        }
    }
}
