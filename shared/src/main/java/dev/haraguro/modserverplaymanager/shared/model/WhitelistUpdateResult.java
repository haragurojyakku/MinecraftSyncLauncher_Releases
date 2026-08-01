package dev.haraguro.modserverplaymanager.shared.model;

import java.util.List;

/**
 * Result of a whitelist add/remove call. appliedLive tells the caller
 * whether the change actually reached the running server via RCON's own
 * `whitelist add`/`remove` (the resolved-uuid-correctly path) or only
 * landed in whitelist.json via a direct file edit because the server
 * wasn't running to ask — the launcher GUI surfaces that distinction so a
 * "still rejected after adding" report is diagnosable instead of silent.
 */
public class WhitelistUpdateResult {

    private List<WhitelistEntry> entries;
    private boolean appliedLive;

    public WhitelistUpdateResult() {
    }

    public WhitelistUpdateResult(List<WhitelistEntry> entries, boolean appliedLive) {
        this.entries = entries;
        this.appliedLive = appliedLive;
    }

    public List<WhitelistEntry> getEntries() {
        return entries;
    }

    public boolean isAppliedLive() {
        return appliedLive;
    }
}
