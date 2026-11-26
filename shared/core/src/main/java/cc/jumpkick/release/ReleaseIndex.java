// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.release;

import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.jsonl.Jsonl;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The documents a release's final run writes beside its files: {@code SHA256SUMS} in {@code
 * sha256sum}'s format, {@code index.json} listing every file, and an object store's {@code
 * latest.json} naming the published release. Each is a function of its entries, so a re-run renders
 * the same bytes.
 */
public final class ReleaseIndex {

    private ReleaseIndex() {}

    /** {@code SHA256SUMS}. */
    public static final String SUMS = "SHA256SUMS";

    /** {@code index.json}. */
    public static final String INDEX = "index.json";

    /** {@code latest.json}. */
    public static final String LATEST = "latest.json";

    /**
     * One file of a release: its name, the module that built it, its platform ({@code null} when
     * neutral), its size in bytes, its lower-case hex SHA-256, and where it is downloaded ({@code
     * null} when the target names its own download URLs).
     */
    public record Entry(
            String name,
            String module,
            @Nullable String platform,
            long size,
            String sha256,
            @Nullable String url) {}

    /**
     * {@code <sha256>  <name>} per file, sorted by name, LF line endings: the bytes jk's own installer
     * verifies and {@code sha256sum -c} reads.
     */
    public static byte[] sha256sums(List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : sorted(entries)) {
            sb.append(e.sha256().toLowerCase(Locale.ROOT))
                    .append("  ")
                    .append(e.name())
                    .append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * {@code {"name","version","tag","files":[{"name","module","os","arch","size","sha256","url"}]}},
     * files sorted by name; {@code os} and {@code arch} only on platform-specific files, {@code url}
     * only when the entry has one.
     */
    public static byte[] index(String name, String version, String tag, List<Entry> entries) {
        StringBuilder files = new StringBuilder("[");
        for (Entry e : sorted(entries)) {
            if (files.length() > 1) files.append(',');
            JsonFields f = JsonFields.object().string("name", e.name()).string("module", e.module());
            if (e.platform() != null) {
                int dash = e.platform().indexOf('-');
                f.string("os", e.platform().substring(0, dash))
                        .string("arch", e.platform().substring(dash + 1));
            }
            f.number("size", e.size()).string("sha256", e.sha256().toLowerCase(Locale.ROOT));
            f.optionalString("url", e.url());
            files.append(f.finish());
        }
        files.append(']');
        String json = JsonFields.object()
                .string("name", name)
                .string("version", version)
                .string("tag", tag)
                .token("files", files.toString())
                .finish();
        return (json + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /** {@code {"version":…, "index":"{version}/index.json"}}. */
    public static byte[] latest(String version) {
        String json = JsonFields.object()
                .string("version", version)
                .string("index", version + "/" + INDEX)
                .finish();
        return (json + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /** The version a {@code latest.json} names, or {@code null} when it names none. */
    public static @Nullable String latestVersion(byte[] latest) {
        return Jsonl.str(new String(latest, StandardCharsets.UTF_8), "version");
    }

    private static List<Entry> sorted(List<Entry> entries) {
        return entries.stream().sorted(Comparator.comparing(Entry::name)).toList();
    }
}
