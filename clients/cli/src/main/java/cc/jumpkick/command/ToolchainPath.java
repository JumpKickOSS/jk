// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.host.SearchPath;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Surgical {@code PATH} edits for {@code jk hook-env}: swap {@code JAVA_HOME/bin} and {@code
 * GRAALVM_HOME/bin} onto the live search path without freezing or replacing unrelated entries (nvm,
 * pyenv, user prepends, …).
 */
public final class ToolchainPath {

    private ToolchainPath() {}

    /**
     * Remove bins for the homes jk is leaving (and the homes it is about to prepend, to avoid
     * duplicates), then prepend {@code toJavaHome/bin} and — when distinct — {@code
     * toGraalHome/bin}. Pass null {@code to*} homes to strip only (session leave / deactivate).
     */
    public static String swap(
            @Nullable String currentPath,
            @Nullable String fromJavaHome,
            @Nullable String fromGraalHome,
            @Nullable String toJavaHome,
            @Nullable String toGraalHome) {
        String path = currentPath == null ? "" : currentPath;
        path = removeHomeBin(fromJavaHome, path);
        path = removeHomeBin(fromGraalHome, path);
        path = removeHomeBin(toJavaHome, path);
        path = removeHomeBin(toGraalHome, path);
        // Graal first, then Java: java/javac win; native-image still resolves from Graal when
        // the JDK home does not ship it.
        String graalBin = binOf(toGraalHome);
        String javaBin = binOf(toJavaHome);
        if (graalBin != null && !graalBin.equals(javaBin)) {
            path = SearchPath.prepend(graalBin, path);
        }
        if (javaBin != null) {
            path = SearchPath.prepend(javaBin, path);
        }
        return path;
    }

    /**
     * Remove every entry naming one of {@code remove} or {@code prepend}, then put {@code prepend} in
     * front, in its order. Directories are matched as {@link Path}s, as in {@link #swap}.
     */
    public static String swapDirs(@Nullable String currentPath, List<String> remove, List<String> prepend) {
        String path = currentPath == null ? "" : currentPath;
        List<Path> drop = new ArrayList<>();
        for (String d : remove) if (d != null && !d.isBlank()) drop.add(Path.of(d));
        for (String d : prepend) if (d != null && !d.isBlank()) drop.add(Path.of(d));
        var kept = new ArrayList<String>();
        for (String entry : SearchPath.entries(path)) {
            boolean match = false;
            for (Path d : drop) match |= samePath(entry, d);
            if (!match) kept.add(entry);
        }
        String out = String.join(SearchPath.SEPARATOR, kept);
        for (int i = prepend.size() - 1; i >= 0; i--) {
            String d = prepend.get(i);
            if (d != null && !d.isBlank()) out = SearchPath.prepend(d, out);
        }
        return out;
    }

    static @Nullable String binOf(@Nullable String home) {
        if (home == null || home.isBlank()) return null;
        return Path.of(home).resolve("bin").toString();
    }

    /**
     * Drop every {@code PATH} entry that names {@code home/bin}, matching by {@link Path} equality
     * so an entry spelled with the other slash direction still matches the host-normalized bin
     * from {@link #binOf}. MSYS-style spellings ({@code /c/Users/...}) parse driveless and do
     * <em>not</em> match — those entries survive the swap.
     */
    private static String removeHomeBin(@Nullable String home, String path) {
        String bin = binOf(home);
        if (bin == null) return path;
        Path binPath = Path.of(bin);
        var kept = new ArrayList<String>();
        for (String entry : SearchPath.entries(path)) {
            if (!samePath(entry, binPath)) kept.add(entry);
        }
        if (kept.isEmpty()) return "";
        return String.join(SearchPath.SEPARATOR, kept);
    }

    private static boolean samePath(String entry, Path target) {
        if (entry == null || entry.isEmpty()) return false;
        try {
            return Path.of(entry).equals(target);
        } catch (InvalidPathException e) {
            return entry.equals(target.toString());
        }
    }
}
