package dev.haraguro.modserverplaymanager.launcher.i18n;

import java.util.Locale;

/**
 * Supported UI languages: English, Japanese, and Swedish — Swedish because
 * Mojang (and Minecraft) is from Sweden.
 */
public enum Lang {
    ENGLISH("en", "English"),
    JAPANESE("ja", "日本語"),
    SWEDISH("sv", "Svenska");

    private final String code;
    private final String displayName;

    Lang(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String code() {
        return code;
    }

    public String displayName() {
        return displayName;
    }

    public Locale toLocale() {
        return Locale.of(code);
    }

    public static Lang byCode(String code) {
        for (Lang lang : values()) {
            if (lang.code.equals(code)) {
                return lang;
            }
        }
        return null;
    }

    /** Maps the JVM/OS default locale to one of our 3 languages, defaulting to English. */
    public static Lang detectFromOs() {
        Lang detected = byCode(Locale.getDefault().getLanguage());
        return detected != null ? detected : ENGLISH;
    }
}
