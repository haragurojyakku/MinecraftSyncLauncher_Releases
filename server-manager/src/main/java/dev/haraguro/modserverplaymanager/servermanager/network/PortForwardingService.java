package dev.haraguro.modserverplaymanager.servermanager.network;

import dev.haraguro.modserverplaymanager.shared.model.PortForwardingStatus;

import java.time.Duration;
import java.util.Optional;

/**
 * Best-effort automatic router port mapping via UPnP, opt-in from the
 * launcher's "host this PC" checkbox — see LauncherApp/LocalServiceLauncher
 * for how mcsync.portforward.* system properties reach here. Mirrors
 * BackupScheduler's enabled-flag-in-constructor + start()/stop() shape.
 *
 * Only maps the Minecraft server port unconditionally; the sync-server port
 * is mapped only when {@code syncServerApiKeySet} is true, since sync-server
 * has no authentication at all without an API key — mapping it to the
 * internet unauthenticated would hand out mod/resource-pack files (and
 * whatever PlayerRoutes/ExternalRoutes grow into) to anyone who finds the port.
 */
public class PortForwardingService {

    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(3);
    private static final String PROTOCOL = "TCP";

    private final boolean enabled;
    private final int minecraftPort;
    private final int syncServerPort;
    private final boolean syncServerApiKeySet;
    private final UpnpPortForwarder upnp = new UpnpPortForwarder();

    private volatile PortForwardingStatus status = new PortForwardingStatus(false, false, false, null, null);
    private UpnpPortForwarder.Gateway gateway;

    public PortForwardingService(boolean enabled, int minecraftPort, int syncServerPort, boolean syncServerApiKeySet) {
        this.enabled = enabled;
        this.minecraftPort = minecraftPort;
        this.syncServerPort = syncServerPort;
        this.syncServerApiKeySet = syncServerApiKeySet;
    }

    public synchronized void start() {
        if (!enabled) {
            System.out.println("server-manager: automatic port forwarding disabled (mcsync.portforward.enabled=false)");
            return;
        }

        Optional<UpnpPortForwarder.Gateway> found = upnp.discover(DISCOVERY_TIMEOUT);
        if (found.isEmpty()) {
            String error = "No UPnP-capable router found (UPnP disabled/unsupported, or a double-NAT/CGNAT "
                    + "connection). Forward ports " + minecraftPort
                    + (syncServerPort > 0 ? " and " + syncServerPort : "") + " manually instead.";
            System.out.println("server-manager: " + error);
            status = new PortForwardingStatus(true, false, false, null, error);
            return;
        }
        gateway = found.get();
        String externalIp = upnp.getExternalIpAddress(gateway).orElse(null);

        boolean minecraftMapped = tryMap(minecraftPort, "ModServerPlayManagerByHaraguro (Minecraft)");
        String error = minecraftMapped ? null : "Failed to map Minecraft server port " + minecraftPort + " via UPnP.";

        boolean syncServerMapped = false;
        if (syncServerPort > 0) {
            if (syncServerApiKeySet) {
                syncServerMapped = tryMap(syncServerPort, "ModServerPlayManagerByHaraguro (sync-server)");
                if (!syncServerMapped && error == null) {
                    error = "Failed to map sync-server port " + syncServerPort + " via UPnP.";
                }
            } else {
                String reason = "sync-server port " + syncServerPort + " not mapped: no API key set "
                        + "(-Dmcsync.api.key) — mapping an unauthenticated API to the internet would be unsafe.";
                System.out.println("server-manager: " + reason);
                if (error == null) {
                    error = reason;
                }
            }
        }

        status = new PortForwardingStatus(true, minecraftMapped, syncServerMapped, externalIp, error);
    }

    /** Best-effort cleanup — a machine reboot or the next start() will re-map from scratch either way. */
    public synchronized void stop() {
        if (gateway == null) {
            return;
        }
        try {
            if (status.isMinecraftPortMapped()) {
                upnp.deleteMapping(gateway, minecraftPort, PROTOCOL);
            }
            if (status.isSyncServerPortMapped()) {
                upnp.deleteMapping(gateway, syncServerPort, PROTOCOL);
            }
        } catch (Exception e) {
            System.out.println("server-manager: failed to remove UPnP port mappings on shutdown: " + e.getMessage());
        }
    }

    public PortForwardingStatus getStatus() {
        return status;
    }

    private boolean tryMap(int port, String description) {
        try {
            upnp.addMapping(gateway, port, PROTOCOL, description);
            System.out.println("server-manager: UPnP mapped port " + port + " (" + description + ")");
            return true;
        } catch (Exception e) {
            System.out.println("server-manager: UPnP failed to map port " + port + ": " + e.getMessage());
            return false;
        }
    }
}
