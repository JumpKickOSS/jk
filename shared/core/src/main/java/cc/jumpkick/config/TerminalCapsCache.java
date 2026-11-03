// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * What the user confirmed they see, per terminal, kept in {@code ~/.jk/terminal-caps.toml}.
 *
 * <p>Each entry is stored with the {@link Fingerprint} it was confirmed under. A later launch
 * recomputes the fingerprint (environment reads and a few {@code stat} calls) and compares it part
 * by part, so a changed font, terminal config file, installed-font directory or terminal version
 * invalidates the entry and names the part that changed. Entries are keyed by {@linkplain
 * #slot terminal slot}, so confirming in one terminal never answers for another.
 *
 * <p>Every method degrades instead of throwing: an unreadable or malformed file reads as empty, and
 * a failed write is dropped. Detection must not be able to fail a build.
 */
public final class TerminalCapsCache {

    /** The facts a confirmation is valid under. Empty parts are facts that could not be determined. */
    public record Fingerprint(String version, String font, String config, String fonts) {

        /** Short stable digest of all parts, used to remember that a staleness was already reported. */
        String key() {
            return sha(version + '\n' + font + '\n' + config + '\n' + fonts);
        }

        /** Names of the parts that differ from {@code stored}, empty when equal. */
        List<String> differences(Fingerprint stored) {
            List<String> out = new ArrayList<>(4);
            if (!version.equals(stored.version)) out.add("terminal version");
            if (!font.equals(stored.font)) out.add("terminal font");
            if (!config.equals(stored.config)) out.add("terminal config file");
            if (!fonts.equals(stored.fonts)) out.add("installed fonts");
            return out;
        }
    }

    /** One stored confirmation. {@code hinted} is the {@link Fingerprint#key} already reported stale. */
    public record Entry(NerdFontCaps caps, String source, String at, Fingerprint fingerprint, String hinted) {}

    /** Outcome of comparing the stored entry to the current fingerprint. */
    public sealed interface Check {
        /** Nothing stored for this terminal. */
        record Absent() implements Check {}

        /** Stored and still valid. */
        record Valid(Entry entry) implements Check {}

        /** Stored but a fingerprint part changed. */
        record Stale(Entry entry, List<String> changed) implements Check {}
    }

    private final @Nullable Path file;

    private TerminalCapsCache(@Nullable Path file) {
        this.file = file;
    }

    /** The user's cache under {@code ~/.jk}. */
    public static TerminalCapsCache real() {
        return new TerminalCapsCache(JkDirs.home().resolve("terminal-caps.toml"));
    }

    /** A cache over an explicit file, for tests. */
    public static TerminalCapsCache at(Path file) {
        return new TerminalCapsCache(file);
    }

    /** A cache that stores nothing and always reads empty. */
    public static TerminalCapsCache none() {
        return new TerminalCapsCache(null);
    }

    /** True when this cache has no backing file. */
    private boolean disabled() {
        return file == null;
    }

    /**
     * Key of the terminal in front of the user: its id, plus for a remote session the client address
     * ssh reports, since a different client is a different screen.
     */
    public static String slot(TerminalIdentity id, Function<String, @Nullable String> env) {
        String slot = id.terminal().id();
        if (id.remote()) {
            String conn = env.apply("SSH_CONNECTION");
            String client = conn == null ? "" : conn.trim().split("\\s+")[0];
            slot += "-ssh-" + client;
        }
        return slot.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
    }

    /** {@link #slot} of the real environment. */
    public static String currentSlot() {
        return slot(TerminalIdentity.current(), BuildEnv::get);
    }

    /** Fingerprint from the lookups the heuristic tiers already make. */
    public static Fingerprint fingerprint(
            TerminalIdentity id, TerminalFonts.Lookup font, InstalledFonts.Scan installed) {
        return new Fingerprint(id.version(), font.font().orElse(""), sha(font.stamp()), sha(installed.stamp()));
    }

    /** Compare the entry stored for {@code slot} with {@code current}. */
    public Check check(String slot, Fingerprint current) {
        Optional<Entry> stored = read(slot);
        if (stored.isEmpty()) return new Check.Absent();
        List<String> changed = current.differences(stored.get().fingerprint());
        return changed.isEmpty() ? new Check.Valid(stored.get()) : new Check.Stale(stored.get(), changed);
    }

    /** The raw entry for {@code slot}, with no fingerprint comparison. */
    public Optional<Entry> read(String slot) {
        Map<String, Map<String, String>> all = load();
        Map<String, String> t = all.get(slot);
        if (t == null) return Optional.empty();
        NerdFontCaps caps = parseCaps(t.get("caps"));
        if (caps == null) return Optional.empty();
        return Optional.of(new Entry(
                caps,
                t.getOrDefault("source", "confirmed"),
                t.getOrDefault("at", ""),
                new Fingerprint(
                        t.getOrDefault("version", ""),
                        t.getOrDefault("font", ""),
                        t.getOrDefault("config", ""),
                        t.getOrDefault("fonts", "")),
                t.getOrDefault("hinted", "")));
    }

    /** Store a confirmation for {@code slot}, replacing any earlier one. Returns false if it could not be written. */
    public boolean put(String slot, NerdFontCaps caps, String source, Fingerprint fp) {
        if (disabled()) return false;
        Map<String, Map<String, String>> all = load();
        Map<String, String> t = new LinkedHashMap<>();
        t.put("caps", capsToken(caps));
        t.put("source", source);
        t.put("at", LocalDate.now().toString());
        t.put("version", fp.version());
        t.put("font", fp.font());
        t.put("config", fp.config());
        t.put("fonts", fp.fonts());
        all.put(slot, t);
        return store(all);
    }

    /**
     * Report a staleness once: true the first time {@code current} is seen stale against the stored
     * entry (and records that), false afterwards.
     */
    public boolean claimHint(String slot, Fingerprint current) {
        if (disabled()) return false;
        Map<String, Map<String, String>> all = load();
        Map<String, String> t = all.get(slot);
        if (t == null) return false;
        String key = current.key();
        if (key.equals(t.get("hinted"))) return false;
        t.put("hinted", key);
        store(all);
        return true;
    }

    /** Forget the entry for {@code slot}. */
    public void remove(String slot) {
        if (disabled()) return;
        Map<String, Map<String, String>> all = load();
        if (all.remove(slot) != null) store(all);
    }

    private static String capsToken(NerdFontCaps caps) {
        if (caps.wedge() && caps.pill()) return "all";
        if (caps.wedge()) return "wedge";
        if (caps.pill()) return "pill";
        return "none";
    }

    private static @Nullable NerdFontCaps parseCaps(@Nullable String token) {
        if (token == null) return null;
        return switch (token) {
            case "all" -> NerdFontCaps.ALL;
            case "wedge" -> NerdFontCaps.WEDGE_ONLY;
            case "pill" -> NerdFontCaps.PILL_ONLY;
            case "none" -> NerdFontCaps.NONE;
            default -> null;
        };
    }

    // ── file format: [terminal.<slot>] sections of key = "value" ──────────────────────────────────

    private static final String SECTION_PREFIX = "terminal.";

    private Map<String, Map<String, String>> load() {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        if (disabled()) return out;
        try {
            Path file = Objects.requireNonNull(this.file);
            if (!Files.isRegularFile(file)) return out;
            Map<String, String> current = null;
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("[") && line.endsWith("]")) {
                    String name = line.substring(1, line.length() - 1).strip();
                    current = name.startsWith(SECTION_PREFIX)
                            ? out.computeIfAbsent(name.substring(SECTION_PREFIX.length()), k -> new LinkedHashMap<>())
                            : null;
                    continue;
                }
                int eq = line.indexOf('=');
                if (current == null || eq <= 0) continue;
                String value = line.substring(eq + 1).strip();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    current.put(line.substring(0, eq).strip(), unescape(value.substring(1, value.length() - 1)));
                }
            }
        } catch (IOException | RuntimeException e) {
            out.clear();
        }
        return out;
    }

    private boolean store(Map<String, Map<String, String>> all) {
        try {
            StringBuilder sb = new StringBuilder("# Written by `jk self setup-terminal`; safe to delete.\n");
            for (var e : all.entrySet()) {
                sb.append("\n[").append(SECTION_PREFIX).append(e.getKey()).append("]\n");
                for (var kv : e.getValue().entrySet()) {
                    sb.append(kv.getKey())
                            .append(" = \"")
                            .append(escape(kv.getValue()))
                            .append("\"\n");
                }
            }
            Path file = Objects.requireNonNull(this.file);
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(n == 'n' ? '\n' : n);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String sha(String s) {
        return s.isEmpty() ? "" : Hashing.sha256Hex(s).substring(0, 12);
    }
}
