package dev.haraguro.modserverplaymanager.launcher.launch;

/** Prints "label: N/total (P%)" lines to stdout. */
public class ConsoleProgressReporter extends ThrottledProgressReporter {

    public ConsoleProgressReporter(String label) {
        super(label);
    }

    @Override
    protected void onMilestone(String label, int completed, int total, int percent) {
        System.out.printf("  %s: %d/%d (%d%%)%n", label, completed, total, percent);
    }
}
