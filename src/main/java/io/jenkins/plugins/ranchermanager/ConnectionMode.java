package io.jenkins.plugins.ranchermanager;

import java.util.Locale;

/**
 * Rancher connection mode on the build step: {@link #INHERIT} (default) or {@link #MANUAL}.
 * Freestyle {@code f:radioBlock inline="true"} and Pipeline both bind a plain string.
 */
final class ConnectionMode {

    static final String INHERIT = "inherit";
    static final String MANUAL = "manual";

    private ConnectionMode() {
    }

    static String normalize(String raw, String defaultMode) {
        if (raw == null || raw.isBlank()) {
            return defaultMode;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (INHERIT.equals(v) || MANUAL.equals(v)) {
            return v;
        }
        return defaultMode;
    }

    static boolean isManual(String mode) {
        return MANUAL.equals(normalize(mode, INHERIT));
    }

    /**
     * True only for {@link #INHERIT}. Form checks that are “not Manual” should use
     * {@code !isManual(mode)} so unknown still falls through to Inherit behaviour.
     */
    static boolean isInherit(String mode) {
        return INHERIT.equals(normalize(mode, INHERIT));
    }
}
