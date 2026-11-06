// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import cc.jumpkick.jdk.HostPlatform;
import cc.jumpkick.jdk.JdkCatalog;
import cc.jumpkick.jdk.JdkLts;
import cc.jumpkick.jdk.SupportedJdk;
import cc.jumpkick.scaffold.NewInputs;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** How {@code jk new} reads its positional directory and flags: target, name, language, deps. */
@NullMarked
public final class NewArgs {

    private NewArgs() {}

    static Optional<String> presetName(@Nullable Path directoryArg, Path cwd) {
        if (directoryArg == null) return Optional.empty();
        if (isCurrentDirArg(directoryArg)) {
            var leaf = cwd.getFileName();
            if (leaf == null) return Optional.empty();
            var s = leaf.toString();
            return (s.isBlank() || s.equals(".")) ? Optional.empty() : Optional.of(s);
        }
        var leaf = directoryArg.getFileName();
        if (leaf == null) return Optional.empty();
        var s = leaf.toString();
        return (s.isBlank() || s.equals(".")) ? Optional.empty() : Optional.of(s);
    }

    /**
     * Final target directory, given the positional arg, the cwd, and the resolved project name.
     * Package-private for unit testing.
     */
    static Path resolveTarget(@Nullable Path directoryArg, Path cwd, String projectName) {
        if (directoryArg != null) {
            if (isCurrentDirArg(directoryArg)) return cwd;
            return cwd.resolve(directoryArg).normalize();
        }
        // No positional → create a subdir under cwd named after the project.
        return cwd.resolve(projectName);
    }

    /** Match the literal {@code "."} forms the user might type. */
    static boolean isCurrentDirArg(Path arg) {
        var raw = arg.toString();
        return raw.equals(".") || raw.equals("./") || raw.equals(".\\");
    }

    static NewInputs.Language parseLanguage(String value) {
        if (value == null || value.isBlank()) {
            return NewInputs.Language.JAVA;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "java" -> NewInputs.Language.JAVA;
            case "kotlin", "kt" -> NewInputs.Language.KOTLIN;
            case "groovy" -> NewInputs.Language.GROOVY;
            case "scala" -> NewInputs.Language.SCALA;
            default ->
                throw new IllegalArgumentException(
                        "jk new: -l must be 'java', 'kotlin', 'groovy', or 'scala', got: " + value);
        };
    }

    static List<String> parseDeps(@Nullable String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        var out = new ArrayList<String>();
        for (var part : csv.split(",")) {
            var trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    /**
     * Current Java LTS feature release, and the one place it is written down. Bumped on each new
     * LTS — a second copy is one path that offers 25 while another pins 21.
     */
    public static final int LATEST_LTS_MAJOR = 25;

    static String deriveMainFqcn(String group, NewInputs.Language lang, boolean compact) {
        return switch (lang) {
            case JAVA -> group + ".Main";
            case KOTLIN -> compact ? "MainKt" : group + ".MainKt";
            case GROOVY -> compact ? "Main" : group + ".Main";
            case SCALA -> compact ? "Main" : group + ".Main";
        };
    }

    /** {@code primary} unless it's empty, in which case {@code fallback} (the offline default). */
    static List<Integer> orElseList(List<Integer> primary, List<Integer> fallback) {
        return primary.isEmpty() ? fallback : primary;
    }

    /**
     * Offline language-major list for one track, from the compiled-in constants — used only
     * when the catalog can't be fetched. LTS majors down to {@link cc.jumpkick.jdk.SupportedJdk#MIN_MAJOR},
     * then (standard track only) the latest non-LTS stable. The native track caps at the latest LTS,
     * since GraalVM tracks LTS releases and we can't know a newer native-capable major offline.
     */
    static List<Integer> offlineMajors(boolean nativeTrack) {
        List<Integer> out = new ArrayList<>();
        for (int v = JdkLts.OFFLINE_LATEST_LTS; v >= SupportedJdk.MIN_MAJOR; v--) {
            if (JdkLts.isLtsMajor(v)) out.add(v);
        }
        int latestStable = JdkLts.OFFLINE_LATEST_STABLE;
        if (!nativeTrack && latestStable > JdkLts.OFFLINE_LATEST_LTS) out.add(latestStable);
        return out;
    }

    /** The newest native-image-capable (GraalVM) Java major, from the catalog or the offline cap. */
    static int maxNativeMajor(@Nullable JdkCatalog catalog) {
        List<Integer> majors = orElseList(
                SupportedJdk.offerableMajors(catalog, true, HostPlatform.currentOs(), HostPlatform.currentArch()),
                offlineMajors(true));
        return majors.stream().mapToInt(Integer::intValue).max().orElse(JdkLts.OFFLINE_LATEST_LTS);
    }
}
