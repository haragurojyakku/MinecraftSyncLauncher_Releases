package dev.haraguro.modserverplaymanager.shared.model;

/**
 * Result of server-manager's best-effort UPnP router port mapping attempt —
 * see server-manager's PortForwardingService. Read-only from the launcher's
 * perspective; enabling/disabling the feature itself happens via the
 * -Dmcsync.portforward.* system properties passed at server-manager startup.
 */
public class PortForwardingStatus {

    private boolean enabled;
    private boolean minecraftPortMapped;
    private boolean syncServerPortMapped;
    private String externalIpAddress;
    private String lastError;

    public PortForwardingStatus() {
    }

    public PortForwardingStatus(boolean enabled, boolean minecraftPortMapped, boolean syncServerPortMapped,
                                 String externalIpAddress, String lastError) {
        this.enabled = enabled;
        this.minecraftPortMapped = minecraftPortMapped;
        this.syncServerPortMapped = syncServerPortMapped;
        this.externalIpAddress = externalIpAddress;
        this.lastError = lastError;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isMinecraftPortMapped() {
        return minecraftPortMapped;
    }

    public boolean isSyncServerPortMapped() {
        return syncServerPortMapped;
    }

    public String getExternalIpAddress() {
        return externalIpAddress;
    }

    public String getLastError() {
        return lastError;
    }
}
