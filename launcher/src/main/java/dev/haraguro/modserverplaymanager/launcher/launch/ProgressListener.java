package dev.haraguro.modserverplaymanager.launcher.launch;

/** Called from downloader worker threads — implementations must be thread-safe. */
public interface ProgressListener {

    ProgressListener NONE = (completed, total) -> {
    };

    void onFileComplete(int completed, int total);
}
