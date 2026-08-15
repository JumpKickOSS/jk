// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plain-text {@code ensure-jdk} progress for the TUI / dashboard detail segment.
 *
 * <p>{@code downloading Temurin 25 ▰▰▰▰▰▱▱▱▱▱ 50%} then {@code installing Temurin 25 ▰…▰ 100%}.
 * Glyphs and percent live in the string so every client can paint the same shape; coloring is
 * client-side.
 */
public final class JdkProgressLabel {

    public static final int BAR_WIDTH = 10;
    public static final char FILLED = '▰';
    public static final char EMPTY = '▱';

    public static final String VERB_DOWNLOADING = "downloading";
    public static final String VERB_INSTALLING = "installing";

    private static final Pattern LINE =
            Pattern.compile("^(downloading|installing) (.+?)(?: ([" + FILLED + EMPTY + "]+) (\\d+)%)?$");

    private JdkProgressLabel() {}

    /**
     * Short tree name: product + major ({@code Temurin 25}), matching the build-row mock. Falls
     * back to {@code JDK} when the feed row is missing a product.
     */
    public static String compactName(JdkCatalog.Entry entry) {
        Objects.requireNonNull(entry, "entry");
        String product = entry.product() == null || entry.product().isBlank() ? "JDK" : entry.product();
        return product + " " + entry.majorVersion();
    }

    /**
     * {@code 0..100}, or {@code -1} when {@code total} is unknown. Floors rather than rounds:
     * "100%" means the last byte arrived, never "99.5% and still downloading" — the
     * downloading/installing boundary the label design hinges on.
     */
    public static int percent(long read, long total) {
        if (total <= 0) return -1;
        long clamped = Math.max(0L, read);
        return (int) Math.min(100L, (100L * clamped) / total);
    }

    public static String downloading(String name, long read, long total) {
        return format(VERB_DOWNLOADING, name, read, total);
    }

    /** Extract / register phase — full bar at 100%. */
    public static String installing(String name) {
        return format(VERB_INSTALLING, name, 1, 1);
    }

    public static String format(String verb, String name, long read, long total) {
        String v = verb == null || verb.isBlank() ? VERB_DOWNLOADING : verb;
        String n = name == null || name.isBlank() ? "JDK" : name;
        int pct = percent(read, total);
        if (pct < 0) return v + " " + n;
        return v + " " + n + " " + bar(pct) + " " + pct + "%";
    }

    public static String bar(int percent) {
        int pct = Math.max(0, Math.min(100, percent));
        int fill = (int) Math.round(BAR_WIDTH * (pct / 100.0));
        if (fill > BAR_WIDTH) fill = BAR_WIDTH;
        char[] cells = new char[BAR_WIDTH];
        for (int i = 0; i < BAR_WIDTH; i++) cells[i] = i < fill ? FILLED : EMPTY;
        return new String(cells);
    }

    /**
     * @param verb {@code downloading} or {@code installing}
     * @param bar ten {@code ▰}/{@code ▱} cells, or empty when the feed omitted archive size
     * @param percent {@code 0..100}, or {@code -1} when there is no bar
     */
    public record Parsed(String verb, String name, String bar, int percent) {
        public boolean hasBar() {
            return bar != null && !bar.isEmpty();
        }
    }

    public static Parsed tryParse(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher m = LINE.matcher(text.trim());
        if (!m.matches()) return null;
        String bar = m.group(3);
        int pct = -1;
        if (m.group(4) != null) {
            try {
                pct = Integer.parseInt(m.group(4));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return new Parsed(m.group(1), m.group(2), bar == null ? "" : bar, pct);
    }
}
