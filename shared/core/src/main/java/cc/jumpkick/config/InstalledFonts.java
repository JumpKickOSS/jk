// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.Os;
import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Which Nerd Font glyph families are installed on this machine? Terminals that fall back per glyph (CoreText,
 * fontconfig) render the Private Use Area codepoints from any installed Nerd Font, whatever font
 * the terminal is set to.
 *
 * <p>Directory listings only, bounded in depth and count; never a subprocess. The {@link Scan#stamp}
 * covers the directories' modification times, so a font installed or removed changes it.
 */
public interface InstalledFonts {

    /** Outcome of one scan. */
    record Scan(NerdFontCaps caps, String stamp) {
        public static final Scan NONE = new Scan(NerdFontCaps.NONE, "");
    }

    /** Scan the font directories. Never throws. */
    Scan scan();

    /** A scan that finds nothing — for tests that do not exercise this tier. */
    InstalledFonts NONE_INSTALLED = () -> Scan.NONE;

    /** The real font directories of this OS under {@code env}. */
    static InstalledFonts real(Function<String, @Nullable String> env) {
        return () -> scan(roots(env));
    }

    /** Font directories for the running OS. */
    static List<Path> roots(Function<String, @Nullable String> env) {
        List<Path> out = new ArrayList<>();
        String home = env.apply("HOME");
        if (home == null || home.isBlank()) home = System.getProperty("user.home");
        Path h = home == null || home.isBlank() ? null : Path.of(home);
        if (Os.isDarwin()) {
            if (h != null) out.add(h.resolve("Library/Fonts"));
            out.add(Path.of("/Library/Fonts"));
        } else if (Os.isWindows()) {
            String local = env.apply("LOCALAPPDATA");
            if (local != null && !local.isBlank()) out.add(Path.of(local, "Microsoft", "Windows", "Fonts"));
            String root = env.apply("SystemRoot");
            out.add(Path.of(root == null || root.isBlank() ? "C:\\Windows" : root, "Fonts"));
        } else {
            String xdg = env.apply("XDG_DATA_HOME");
            if (xdg != null && !xdg.isBlank() && Path.of(xdg).isAbsolute()) {
                out.add(Path.of(xdg, "fonts"));
            } else if (h != null) {
                out.add(h.resolve(".local/share/fonts"));
            }
            if (h != null) out.add(h.resolve(".fonts"));
            out.add(Path.of("/usr/local/share/fonts"));
            out.add(Path.of("/usr/share/fonts"));
        }
        return out;
    }

    /** Deepest directory level visited below a root. */
    int MAX_DEPTH = 3;

    /** Entries examined across all roots before the scan gives up. */
    int MAX_ENTRIES = 20_000;

    /** Walk {@code roots}; exposed so tests can point at a temp tree. */
    static Scan scan(List<Path> roots) {
        try {
            var stamp = new StringBuilder();
            NerdFontCaps[] found = {NerdFontCaps.NONE};
            int[] budget = {MAX_ENTRIES};
            for (Path root : roots) {
                walk(root, 0, found, budget, stamp);
            }
            return new Scan(found[0], stamp.toString());
        } catch (RuntimeException e) {
            return Scan.NONE;
        }
    }

    private static void walk(Path dir, int depth, NerdFontCaps[] found, int[] budget, StringBuilder stamp) {
        if (budget[0] <= 0 || !Files.isDirectory(dir)) return;
        try {
            stamp.append(dir)
                    .append('=')
                    .append(Files.getLastModifiedTime(dir).toMillis())
                    .append(';');
        } catch (IOException e) {
            return;
        }
        List<Path> subdirs = new ArrayList<>();
        try {
            PathUtil.forEachChild(dir, (entry, attrs) -> {
                if (--budget[0] <= 0) return false;
                if (attrs.isDirectory()) {
                    if (depth < MAX_DEPTH) subdirs.add(entry);
                } else {
                    found[0] = found[0].max(fontFileCaps(entry.getFileName().toString()));
                }
                return true;
            });
        } catch (IOException | RuntimeException e) {
            // Unreadable directory: it contributes its stamp and nothing else.
        }
        subdirs.sort(null);
        for (Path sub : subdirs) walk(sub, depth + 1, found, budget, stamp);
    }

    /** Glyph families a font file's name promises: Nerd Fonts and the Symbols fallback, or Powerline. */
    static NerdFontCaps fontFileCaps(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || !(lower.endsWith(".ttf") || lower.endsWith(".otf") || lower.endsWith(".ttc"))) {
            return NerdFontCaps.NONE;
        }
        if (lower.contains("nerdfont") || lower.contains("nerd-font") || lower.contains("nerd_font")) {
            return NerdFontCaps.ALL;
        }
        return NerdFontNames.caps(fileName.substring(0, dot));
    }
}
