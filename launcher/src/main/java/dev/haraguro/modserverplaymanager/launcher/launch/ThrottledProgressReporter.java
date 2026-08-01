package dev.haraguro.modserverplaymanager.launcher.launch;

/**
 * Fires {@link #onMilestone} at most every 5% (plus always at 100%) so a
 * multi-thousand-file asset sync doesn't flood a console or a GUI log with
 * one line per file. Shared by {@link ConsoleProgressReporter} and the GUI's
 * progress reporter — without this, a packaged app just sits there looking
 * frozen for however long a few hundred MB takes to download.
 */
public abstract class ThrottledProgressReporter implements ProgressListener {

    private final String label;
    private int lastPercentReported = -1;

    protected ThrottledProgressReporter(String label) {
        this.label = label;
    }

    @Override
    public final synchronized void onFileComplete(int completed, int total) {
        if (total <= 0) {
            return;
        }
        int percent = completed * 100 / total;
        boolean milestone = percent / 5 > lastPercentReported / 5;
        if (completed == total || (milestone && percent != lastPercentReported)) {
            lastPercentReported = percent;
            onMilestone(label, completed, total, percent);
        }
    }

    protected abstract void onMilestone(String label, int completed, int total, int percent);
}
