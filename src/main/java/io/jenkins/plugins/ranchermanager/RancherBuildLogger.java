package io.jenkins.plugins.ranchermanager;

import hudson.model.TaskListener;

import java.io.PrintStream;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Build console for operators; Jenkins JUL is not spammed at INFO.
 * WARN/ERROR go to the console and JUL; INFO is console-only (JUL FINE); DEBUG/FINE goes to JUL
 * and optionally to the console when {@code verboseLogging} is on.
 */
final class RancherBuildLogger implements AutoCloseable {

    static final String PREFIX = "Rancher:";
    static final String TITLE_MANIFEST = "Rancher Manifest Deployment";
    static final String TITLE_HELM = "Rancher Helm Deployment";

    private static final String BANNER_SIDE = "========";

    private final Logger jul;
    private final TaskListener listener;
    private final boolean verbose;

    private boolean opened;
    private boolean closed;
    private boolean errorEmitted;

    RancherBuildLogger(Logger jul, TaskListener listener, boolean verbose) {
        this.jul = jul == null ? Logger.getLogger(RancherBuildLogger.class.getName()) : jul;
        this.listener = listener;
        this.verbose = verbose;
    }

    boolean hasLoggedError() {
        return errorEmitted;
    }

    void open(String title) {
        if (opened) {
            return;
        }
        opened = true;
        closed = false;
        printlnRaw("");
        printlnRaw(bannerHeader(title));
    }

    @Override
    public void close() {
        if (!opened || closed) {
            return;
        }
        closed = true;
    }

    void info(String message) {
        emit(Level.INFO, message, true);
    }

    void debug(String message) {
        emit(Level.FINE, message, verbose);
    }

    void http(String method, String path, long durationMs, String note) {
        String m = method == null || method.isBlank() ? "GET" : method.trim().toUpperCase(Locale.ROOT);
        String p = path == null || path.isBlank() ? "/" : path.trim();
        StringBuilder sb = new StringBuilder(m).append(' ').append(p)
                .append(" (").append(Math.max(0L, durationMs)).append("ms)");
        if (note != null && !note.isBlank()) {
            sb.append(" — ").append(note.trim());
        }
        debug(sb.toString());
    }

    void error(String message) {
        if (errorEmitted) {
            return;
        }
        errorEmitted = true;
        emit(Level.SEVERE, message, true);
    }

    void errorJul(String message, Throwable thrown) {
        if (!jul.isLoggable(Level.SEVERE)) {
            return;
        }
        String line = formatLine(Level.SEVERE, firstLine(message));
        if (thrown == null) {
            jul.log(Level.SEVERE, line);
        } else {
            jul.log(Level.SEVERE, line, thrown);
        }
    }

    static String formatDuration(long elapsedMs) {
        long ms = Math.max(0L, elapsedMs);
        if (ms < 1000L) {
            return ms + "ms";
        }
        return String.format(Locale.ROOT, "%.1fs", ms / 1000.0d);
    }

    void summaryWithDuration(long startedNs, Map<String, String> fields) {
        Map<String, String> out = fields == null ? summaryFields() : fields;
        long elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L;
        out.put("duration", formatDuration(elapsedMs));
        info(formatSummary(out));
    }

    static String formatSummary(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder("Summary");
        if (fields == null || fields.isEmpty()) {
            return sb.toString();
        }
        for (Map.Entry<String, String> e : fields.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            if (key == null || key.isBlank() || value == null || value.isBlank()) {
                continue;
            }
            sb.append(' ').append(key.trim()).append('=').append(value.trim());
        }
        return sb.toString();
    }

    static LinkedHashMap<String, String> summaryFields() {
        return new LinkedHashMap<>();
    }

    static String formatConnection(ResolvedConnection connection) {
        String name = connection == null || connection.displayName == null
                ? ""
                : connection.displayName.trim();
        String mode = connection == null || connection.mode == null
                ? ""
                : connection.mode.trim();
        return "Connection=" + name + " mode=" + mode;
    }

    static String bannerHeader(String title) {
        String t = title == null || title.isBlank() ? "Rancher Manager" : title.trim();
        return BANNER_SIDE + " " + t + " " + BANNER_SIDE;
    }

    static String consoleLabel(Level level) {
        if (level == null) {
            return "INFO";
        }
        if (level.intValue() >= Level.SEVERE.intValue()) {
            return "ERROR";
        }
        if (level.intValue() >= Level.WARNING.intValue()) {
            return "WARN";
        }
        if (level.intValue() < Level.INFO.intValue()) {
            return "DEBUG";
        }
        return "INFO";
    }

    static String stripPrefix(String message) {
        String body = message == null ? "" : message.trim();
        if (body.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            body = body.substring(PREFIX.length()).trim();
        }
        return body;
    }

    static String capitalizeMessage(String message) {
        String body = stripPrefix(message);
        if (body.isEmpty()) {
            return body;
        }
        char c = body.charAt(0);
        if (Character.isLetter(c) && Character.isLowerCase(c)) {
            return Character.toUpperCase(c) + body.substring(1);
        }
        return body;
    }

    static String formatLine(Level level, String message) {
        return "[" + consoleLabel(level) + "] " + capitalizeMessage(message);
    }

    static String safeRequestPath(URI uri) {
        if (uri == null) {
            return "/";
        }
        String path = uri.getRawPath();
        if (path == null || path.isBlank()) {
            path = "/";
        }
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return path;
        }
        return path + "?" + query;
    }

    private void emit(Level level, String message, boolean toConsole) {
        boolean writeConsole = toConsole && listener != null;
        Level julLevel = level == Level.INFO ? Level.FINE : level;
        boolean logJul = jul.isLoggable(julLevel);
        if (!logJul && !writeConsole) {
            return;
        }
        String body = capitalizeMessage(message);
        String[] lines = body.isEmpty() ? new String[] {""} : body.split("\\R", -1);
        String first = formatLine(level, lines[0]);
        if (logJul) {
            jul.log(julLevel, first);
        }
        if (writeConsole) {
            PrintStream out = listener.getLogger();
            out.println(first);
            for (int i = 1; i < lines.length; i++) {
                out.println(lines[i]);
            }
        }
    }

    private static String firstLine(String message) {
        String body = capitalizeMessage(message);
        int cr = body.indexOf('\r');
        int lf = body.indexOf('\n');
        int nl;
        if (cr < 0) {
            nl = lf;
        } else if (lf < 0) {
            nl = cr;
        } else {
            nl = Math.min(cr, lf);
        }
        return nl < 0 ? body : body.substring(0, nl);
    }

    private void printlnRaw(String line) {
        if (listener == null) {
            return;
        }
        listener.getLogger().println(line);
    }
}
