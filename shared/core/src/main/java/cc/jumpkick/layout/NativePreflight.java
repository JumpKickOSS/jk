// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import cc.jumpkick.config.TomlScan;
import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.host.GraalLauncher;
import cc.jumpkick.lock.ManifestPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk native} pre-checks: a GraalVM home with {@code native-image}, and exactly one main.
 * Messages stay under 78 characters so they fit a CommandWedge line.
 */
public final class NativePreflight {

    public static final String GRAAL_UNSET = "GRAALVM_HOME is not set.";
    /**
     * Reads the launcher name and the searched directories out of {@link GraalLauncher} rather than
     * restating them: this message previously named {@code $GRAALVM_HOME/bin} while the search it
     * described could not see {@code lib/svm/bin} at all. Stays under 78 characters
     * ({@code NativePreflightTest.messages_are_terse}) — a second searched directory would have to
     * be short, or the message would have to stop enumerating them.
     */
    public static final String NATIVE_IMAGE_MISSING =
            GraalLauncher.NAME + " not found in $GRAALVM_HOME (" + GraalLauncher.searchedDirs() + ").";

    public static final String NO_MAIN = "no main class — set [application] main or pass --main.";
    public static final String MANY_MAINS = "multiple main classes — set [application] main or pass --main.";

    private static final Pattern JAVA_MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(");
    private static final Pattern KOTLIN_MAIN = Pattern.compile("\\bfun\\s+main\\s*\\(");

    private NativePreflight() {}

    /** {@code $GRAALVM_HOME} is set and holds a {@code native-image} launcher ({@link GraalLauncher}). */
    public sealed interface Graal permits Graal.Ok, Graal.Fail {
        record Ok(Path home) implements Graal {}

        record Fail(String message) implements Graal {}
    }

    /** Specified or discovered main: one, none, or several. */
    public sealed interface Main permits Main.Unique, Main.None, Main.Ambiguous {
        record Unique(String mainClass) implements Main {}

        record None() implements Main {}

        record Ambiguous() implements Main {}
    }

    public static Graal graal(String graalHomeEnv) {
        if (graalHomeEnv == null || graalHomeEnv.isBlank()) return new Graal.Fail(GRAAL_UNSET);
        Path home = Path.of(graalHomeEnv);
        return GraalLauncher.in(home).isPresent() ? new Graal.Ok(home) : new Graal.Fail(NATIVE_IMAGE_MISSING);
    }

    /**
     * {@code --main} / {@code [native].main} / {@code [image].main} / {@code [application]
     * main}, else a classfile or source scan.
     */
    public static Main resolveMain(Path moduleDir, String cliOverride) {
        String specified = specifiedMain(moduleDir, cliOverride);
        if (specified != null) return new Main.Unique(specified);
        return discoverMain(moduleDir);
    }

    public static String failMessage(Main main) {
        return switch (main) {
            case Main.None() -> NO_MAIN;
            case Main.Ambiguous() -> MANY_MAINS;
            case Main.Unique _ -> throw new IllegalArgumentException("unique main is not a failure");
        };
    }

    /**
     * Declared entry point only — no scan. Order matches {@code NativePlans.resolveMain} then
     * {@code [application] main}.
     */
    public static @Nullable String specifiedMain(Path moduleDir, @Nullable String cliOverride) {
        if (notBlank(cliOverride)) return cliOverride;
        Path toml = moduleDir.resolve(ManifestPaths.MANIFEST);
        if (!Files.isRegularFile(toml)) return null;
        var scan = TomlScan.scan(toml, "native.main", "image.main", "application.main");
        String fromNative = scan.get("native.main");
        if (notBlank(fromNative)) return fromNative;
        String fromImage = scan.get("image.main");
        if (notBlank(fromImage)) return fromImage;
        String fromApp = scan.get("application.main");
        return notBlank(fromApp) ? fromApp : null;
    }

    private static Main discoverMain(Path moduleDir) {
        Path classes = classesDir(moduleDir);
        if (Files.isDirectory(classes)) {
            try {
                List<String> found = MainClassScanner.scan(classes);
                if (found.size() == 1) return new Main.Unique(found.getFirst());
                if (found.size() > 1) return new Main.Ambiguous();
            } catch (IOException ignored) {
                // unreadable classes — fall through to sources
            }
        }
        List<String> sources = scanSourceMains(moduleDir);
        if (sources.size() == 1) return new Main.Unique(sources.getFirst());
        if (sources.size() > 1) return new Main.Ambiguous();
        return new Main.None();
    }

    static Path classesDir(Path moduleDir) {
        Path abs = moduleDir.toAbsolutePath().normalize();
        Path ws = abs;
        try {
            ws = WorkspaceLocator.findRoot(abs).orElse(abs);
        } catch (IOException ignored) {
        }
        return BuildLayout.moduleTargetDir(ws, abs).resolve("classes").resolve("main");
    }

    static List<String> scanSourceMains(Path moduleDir) {
        List<String> found = new ArrayList<>();
        for (ModuleLayout.Root root : ModuleLayout.diskRoots(moduleDir)) {
            if (root.kind() != ModuleLayout.Kind.SOURCE) continue;
            Path dir = moduleDir.resolve(root.relative());
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path file : walk.filter(Files::isRegularFile).toList()) {
                    if (sourceHasMain(file)) found.add(file.toString());
                }
            } catch (IOException ignored) {
            }
        }
        return found;
    }

    private static boolean sourceHasMain(Path file) {
        String name = file.getFileName().toString();
        boolean kotlin = name.endsWith(".kt") || name.endsWith(".kts");
        if (!kotlin && !name.endsWith(".java") && !name.endsWith(".groovy")) return false;
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            return false;
        }
        boolean inBlock = false;
        for (String raw : lines) {
            String line = raw;
            if (inBlock) {
                int end = line.indexOf("*/");
                if (end < 0) continue;
                line = line.substring(end + 2);
                inBlock = false;
            }
            int block = line.indexOf("/*");
            if (block >= 0) {
                int end = line.indexOf("*/", block + 2);
                if (end < 0) {
                    line = line.substring(0, block);
                    inBlock = true;
                } else {
                    line = line.substring(0, block) + line.substring(end + 2);
                }
            }
            int slash = line.indexOf("//");
            if (slash >= 0) line = line.substring(0, slash);
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (kotlin
                    ? KOTLIN_MAIN.matcher(trimmed).find()
                    : JAVA_MAIN.matcher(trimmed).find()) {
                return true;
            }
        }
        return false;
    }

    private static boolean notBlank(@Nullable String s) {
        return s != null && !s.isBlank();
    }
}
