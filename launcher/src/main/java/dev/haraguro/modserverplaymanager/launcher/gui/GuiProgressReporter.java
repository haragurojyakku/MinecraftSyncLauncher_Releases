package dev.haraguro.modserverplaymanager.launcher.gui;

import dev.haraguro.modserverplaymanager.launcher.i18n.Messages;
import dev.haraguro.modserverplaymanager.launcher.launch.ThrottledProgressReporter;
import javafx.application.Platform;

/** Updates the window's status line + progress bar instead of printing to stdout. */
public class GuiProgressReporter extends ThrottledProgressReporter {

    private final LauncherWindow window;

    public GuiProgressReporter(String label, LauncherWindow window) {
        super(label);
        this.window = window;
    }

    @Override
    protected void onMilestone(String label, int completed, int total, int percent) {
        String text = "%s: %d/%d (%d%%)".formatted(translateLabel(label), completed, total, percent);
        Platform.runLater(() -> {
            window.setStatusRaw(text);
            window.setProgress(percent / 100.0);
            window.log("  " + text);
        });
    }

    private static String translateLabel(String internalLabel) {
        return switch (internalLabel) {
            case "mods" -> Messages.get("phase.mods");
            case "resourcepacks" -> Messages.get("phase.resourcepacks");
            case "client+libraries" -> Messages.get("phase.clientLibraries");
            case "assets" -> Messages.get("phase.assets");
            case "launcherUpdate" -> Messages.get("phase.launcherUpdate");
            default -> internalLabel;
        };
    }
}
