package dev.haraguro.modserverplaymanager.shared.model;

import java.util.List;

/**
 * Full snapshot of what the server's mods/resourcepacks folders currently
 * contain. The launcher diffs this against its local install and downloads
 * whatever's missing or changed (by sha1) via Routes.SYNC_MODS_DOWNLOAD /
 * SYNC_RESOURCEPACKS_DOWNLOAD.
 *
 * disabledMods/disabledResourcePacks list files that exist server-side but
 * are currently disabled — separate from mods/resourcePacks (which are
 * enabled-only) so the launcher can tell "disabled" apart from "no longer on
 * the server at all" and react differently: mirror a disable locally (rename
 * its own copy aside, see ModSyncService) rather than deleting it, so
 * re-enabling later doesn't require a redownload.
 */
public class SyncManifest {

    private String minecraftVersion;
    private String fabricLoaderVersion;
    private List<SyncFile> mods;
    private List<SyncFile> resourcePacks;
    private List<SyncFile> disabledMods;
    private List<SyncFile> disabledResourcePacks;

    public SyncManifest() {
    }

    public SyncManifest(String minecraftVersion, String fabricLoaderVersion,
                         List<SyncFile> mods, List<SyncFile> resourcePacks,
                         List<SyncFile> disabledMods, List<SyncFile> disabledResourcePacks) {
        this.minecraftVersion = minecraftVersion;
        this.fabricLoaderVersion = fabricLoaderVersion;
        this.mods = mods;
        this.resourcePacks = resourcePacks;
        this.disabledMods = disabledMods;
        this.disabledResourcePacks = disabledResourcePacks;
    }

    public String getMinecraftVersion() {
        return minecraftVersion;
    }

    public String getFabricLoaderVersion() {
        return fabricLoaderVersion;
    }

    public List<SyncFile> getMods() {
        return mods;
    }

    public List<SyncFile> getResourcePacks() {
        return resourcePacks;
    }

    public List<SyncFile> getDisabledMods() {
        return disabledMods;
    }

    public List<SyncFile> getDisabledResourcePacks() {
        return disabledResourcePacks;
    }
}
