// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.plugin.protocol.JUnitUniqueIds;
import cc.jumpkick.util.MinimalXml;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Thread-safe accumulator for per-test results that writes one Gradle-compatible {@code
 * TEST-<classname>.xml} file per test class to a target directory.
 *
 * <p>In parallel mode all workers share one instance; synchronization is on the entry list. {@link
 * #writeAll(Path)} is called once after all workers have joined.
 *
 * <p>The output format matches Gradle's JUnit XML report schema so the files are consumable by CI
 * dashboards, IntelliJ, and test-aggregation tools without additional configuration.
 */
public final class XmlTestReport {

    private record Entry(
            String className,
            String displayName,
            long durationMs,
            @Nullable String failureType,
            @Nullable String failureMessage,
            @Nullable String failureStack,
            @Nullable String skipReason) {}

    private final List<Entry> entries = new ArrayList<>();

    /**
     * What each class's JVM printed while the class ran, by class name — the {@code system-out}
     * of its {@code testsuite}. The fork merges stderr into stdout, so there is one stream and
     * {@code system-err} stays empty.
     */
    private final Map<String, StringBuilder> output = new LinkedHashMap<>();

    /**
     * Bound on one class's captured output. A suite that logs at debug for an hour must not turn
     * its report into the log; what fits is the head, and the tail says how much was cut.
     */
    static final int MAX_OUTPUT_CHARS = 256 * 1024;

    private final String timestamp;
    private final String hostname;

    public XmlTestReport() {
        this.timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            host = "localhost";
        }
        this.hostname = host;
    }

    /**
     * Record a finished test (passed, failed, or aborted). {@code throwableJson} is the raw nested
     * JSON object from the protocol event's {@code throwable} field — {@code null} for a passing
     * test.
     */
    public synchronized void recordFinished(
            String uniqueId, String display, long durationMs, @Nullable String throwableJson) {
        String className = classNameFrom(uniqueId);
        String failureType = null, failureMessage = null, failureStack = null;
        if (throwableJson != null) {
            failureType = Jsonl.str(throwableJson, "class");
            failureMessage = Jsonl.str(throwableJson, "message");
            failureStack = Jsonl.str(throwableJson, "stack");
        }
        entries.add(new Entry(className, display, durationMs, failureType, failureMessage, failureStack, null));
    }

    /**
     * Record a skipped test. {@code reason} is the skip reason from the protocol event, may be null.
     */
    public synchronized void recordSkipped(String uniqueId, String display, @Nullable String reason) {
        String className = classNameFrom(uniqueId);
        entries.add(new Entry(className, display, 0, null, null, null, reason != null ? reason : ""));
    }

    /**
     * Record one line the test JVM printed while {@code className} was running. Lines past
     * {@link #MAX_OUTPUT_CHARS} are counted, not kept.
     */
    public synchronized void recordOutput(String className, String line) {
        StringBuilder sb = output.computeIfAbsent(className, k -> new StringBuilder());
        if (sb.length() >= MAX_OUTPUT_CHARS) {
            truncatedLines.merge(className, 1, Integer::sum);
            return;
        }
        int room = MAX_OUTPUT_CHARS - sb.length();
        if (line.length() + 1 > room) {
            sb.append(line, 0, Math.max(0, room - 1)).append('\n');
            truncatedLines.merge(className, 1, Integer::sum);
            return;
        }
        sb.append(line).append('\n');
    }

    /** Lines {@link #recordOutput} could not keep, by class. */
    private final Map<String, Integer> truncatedLines = new LinkedHashMap<>();

    /** The captured output of {@code className} as {@code system-out} carries it; empty when none. */
    String outputOf(String className) {
        StringBuilder sb = output.get(className);
        if (sb == null) return "";
        Integer cut = truncatedLines.get(className);
        if (cut == null) return sb.toString();
        return sb + "... output truncated (" + cut + " more line(s))\n";
    }

    /**
     * Write one {@code TEST-<classname>.xml} per accumulated class into {@code dir}, creating the
     * directory if needed. Silently no-ops when no test events were recorded. Every class gets its
     * attempt: a class whose file cannot be written is reported after the rest have landed, as the
     * first such failure.
     */
    public synchronized void writeAll(Path dir) throws IOException {
        if (entries.isEmpty()) return;
        Files.createDirectories(dir);

        Map<String, List<Entry>> byClass = new LinkedHashMap<>();
        for (Entry e : entries) {
            byClass.computeIfAbsent(e.className(), k -> new ArrayList<>()).add(e);
        }

        IOException first = null;
        for (var kv : byClass.entrySet()) {
            Path file = dir.resolve("TEST-" + fileNameComponent(kv.getKey()) + ".xml");
            try {
                Files.writeString(file, buildXml(kv.getKey(), kv.getValue()));
            } catch (IOException e) {
                if (first == null) first = e;
            }
        }
        if (first != null) throw first;
    }

    /**
     * The class name as a file-name component: anything outside {@code [A-Za-z0-9._$-]} becomes
     * {@code _}, so an id-shaped name with {@code /} or {@code :} cannot point outside {@code dir}
     * or at a name Windows refuses.
     */
    static String fileNameComponent(String className) {
        StringBuilder sb = new StringBuilder(className.length());
        for (int i = 0; i < className.length(); i++) {
            char c = className.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '.'
                    || c == '_'
                    || c == '$'
                    || c == '-';
            sb.append(ok ? c : '_');
        }
        return sb.isEmpty() ? "_" : sb.toString();
    }

    private String buildXml(String className, List<Entry> classEntries) {
        int tests = classEntries.size(), failures = 0, skipped = 0;
        long totalMs = 0;
        for (Entry e : classEntries) {
            totalMs += e.durationMs();
            if (e.failureType() != null) failures++;
            if (e.skipReason() != null) skipped++;
        }

        var sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<testsuite name=\"").append(esc(className)).append('"');
        sb.append(" tests=\"").append(tests).append('"');
        sb.append(" skipped=\"").append(skipped).append('"');
        sb.append(" failures=\"").append(failures).append('"');
        sb.append(" errors=\"0\"");
        sb.append(" timestamp=\"").append(timestamp).append('"');
        sb.append(" hostname=\"").append(esc(hostname)).append('"');
        sb.append(" time=\"").append(String.format("%.3f", totalMs / 1000.0)).append("\">\n");
        sb.append("  <properties/>\n");

        for (Entry e : classEntries) {
            sb.append("  <testcase name=\"").append(esc(e.displayName())).append('"');
            sb.append(" classname=\"").append(esc(e.className())).append('"');
            sb.append(" time=\"")
                    .append(String.format("%.3f", e.durationMs() / 1000.0))
                    .append('"');
            if (e.skipReason() != null) {
                sb.append(">\n    <skipped");
                if (!e.skipReason().isEmpty())
                    sb.append(" message=\"").append(esc(e.skipReason())).append('"');
                sb.append("/>\n  </testcase>\n");
            } else if (e.failureType() != null) {
                sb.append(">\n    <failure");
                if (e.failureMessage() != null && !e.failureMessage().isEmpty())
                    sb.append(" message=\"").append(esc(e.failureMessage())).append('"');
                sb.append(" type=\"").append(esc(e.failureType())).append('"').append('>');
                if (e.failureStack() != null && !e.failureStack().isEmpty()) sb.append(cdata(e.failureStack()));
                sb.append("</failure>\n  </testcase>\n");
            } else {
                sb.append("/>\n");
            }
        }

        sb.append("  <system-out>").append(cdata(outputOf(className))).append("</system-out>\n");
        sb.append("  <system-err><![CDATA[]]></system-err>\n");
        sb.append("</testsuite>\n");
        return sb.toString();
    }

    /**
     * The segments engines use for the class-like node when there is no {@code [class:…]}: Spock and
     * Kotest name the spec, Cucumber the feature. In lookup order. Vintage's {@code [runner:…]} is
     * read by the shared walk itself.
     */
    private static final List<String> CLASS_LIKE_SEGMENTS = List.of("spec", "feature");

    /**
     * The test class a uniqueId belongs to: the shared {@link JUnitUniqueIds} walk over
     * {@code [class:…]}/{@code [nested-class:…]} and Vintage's {@code [runner:…]}/{@code [test:…]}
     * first, then the engine-specific class-like segment, then the engine id, and the raw id only
     * when the id has no recognisable segment at all.
     */
    static String classNameFrom(String uniqueId) {
        String cls = JUnitUniqueIds.classOf(uniqueId);
        if (!cls.isEmpty()) return cls;
        for (String segment : CLASS_LIKE_SEGMENTS) {
            String value = JUnitUniqueIds.segment(uniqueId, segment);
            if (!value.isEmpty()) return value;
        }
        String engine = JUnitUniqueIds.engineOf(uniqueId);
        return engine.isEmpty() ? uniqueId : engine;
    }

    private static String esc(String s) {
        return s == null ? "" : MinimalXml.escapeAttr(s);
    }

    private static String cdata(String s) {
        return "<![CDATA[" + s.replace("]]>", "]]]]><![CDATA[>") + "]]>";
    }
}
