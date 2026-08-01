package dev.haraguro.modserverplaymanager.launcher.launch;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.Set;

/**
 * Evaluates the "rules" arrays Mojang's version JSON attaches to libraries
 * and conditional arguments (os match, feature flags like demo/resolution/
 * quick-play). Feature flags not present in {@code enabledFeatures} are
 * treated as false, matching how the official launcher only sets the
 * feature it actually supports for a given launch (e.g. it only sets
 * is_quick_play_multiplayer when you asked it to connect somewhere).
 */
public final class Rules {

    private static final String CURRENT_OS = detectOs();

    private Rules() {
    }

    public static boolean allows(JsonArray rules) {
        return allows(rules, Set.of());
    }

    public static boolean allows(JsonArray rules, Set<String> enabledFeatures) {
        if (rules == null || rules.isEmpty()) {
            return true;
        }
        // Mojang semantics: default disallow, each rule can flip the running
        // verdict; the last matching rule wins.
        boolean allowed = false;
        for (var element : rules) {
            JsonObject rule = element.getAsJsonObject();
            if (!ruleMatches(rule, enabledFeatures)) {
                continue;
            }
            allowed = "allow".equals(rule.get("action").getAsString());
        }
        return allowed;
    }

    private static boolean ruleMatches(JsonObject rule, Set<String> enabledFeatures) {
        if (rule.has("os") && !osMatches(rule.getAsJsonObject("os"))) {
            return false;
        }
        if (rule.has("features") && !featuresMatch(rule.getAsJsonObject("features"), enabledFeatures)) {
            return false;
        }
        return true;
    }

    private static boolean featuresMatch(JsonObject features, Set<String> enabledFeatures) {
        for (Map.Entry<String, com.google.gson.JsonElement> entry : features.entrySet()) {
            boolean required = entry.getValue().getAsBoolean();
            if (enabledFeatures.contains(entry.getKey()) != required) {
                return false;
            }
        }
        return true;
    }

    private static boolean osMatches(JsonObject os) {
        if (os.has("name") && !os.get("name").getAsString().equals(CURRENT_OS)) {
            return false;
        }
        if (os.has("arch")) {
            String arch = os.get("arch").getAsString();
            String actual = System.getProperty("os.arch", "");
            if (!actual.contains(arch)) {
                return false;
            }
        }
        return true;
    }

    private static String detectOs() {
        String name = System.getProperty("os.name", "").toLowerCase();
        if (name.contains("win")) {
            return "windows";
        }
        if (name.contains("mac") || name.contains("darwin")) {
            return "osx";
        }
        return "linux";
    }
}
