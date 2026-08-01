package dev.haraguro.modserverplaymanager.launcher.manager;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;

/**
 * Plain TCP reachability check against a Minecraft "host:port" address, run
 * from the connecting side — independent of server-manager's own admin API
 * (see ManagerClient), which a joining player normally has no key for and
 * which may not even be reachable from outside the host's LAN. This is what
 * actually answers "can I join this server right now", vs. the manager
 * API's "is server-manager itself reachable and reporting RUNNING".
 */
public final class GameServerConnectivityChecker {

    private static final int DEFAULT_PORT = 25565;

    private GameServerConnectivityChecker() {
    }

    /** @param hostPort "host" or "host:port" (port defaults to 25565, same as vanilla Minecraft). */
    public static boolean isReachable(String hostPort, Duration timeout) {
        if (hostPort == null || hostPort.isBlank()) {
            return false;
        }
        String trimmed = hostPort.trim();
        int colon = trimmed.lastIndexOf(':');
        String host = colon > 0 ? trimmed.substring(0, colon) : trimmed;
        int port = DEFAULT_PORT;
        if (colon > 0) {
            try {
                port = Integer.parseInt(trimmed.substring(colon + 1).trim());
            } catch (NumberFormatException e) {
                return false;
            }
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) timeout.toMillis());
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
