// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import cc.jumpkick.config.TomlScan;
import cc.jumpkick.config.WorkspaceLocator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * {@code jk native} pre-checks: a GraalVM home with {@code native-image}, and exactly one main.
 * Messages stay under 78 characters so they fit a CommandWedge line.
 */
public final class NativePreflight {

    public static final String GRAAL_UNSET = "GRAALVM_HOME is not set.";
    public static final String NATIVE_IMAGE_MISSING = "native-image not found in $GRAALVM_HOME/bin.";
    public static final String NO_MAIN = "no main class — set [application] main or pass --main.";
    public static final String MANY_MAINS = "multiple main classes — set [application] main or pass --main.";

    private static final Pattern JAVA_MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(");
    private static final Pattern KOTLIN_MAIN = Pattern.compile("\\bfun\\s+main\\s*\\(");

    private NativePreflight() {}

    /** {@code $GRAALVM_HOME} is set and contains {@code bin/native-image}. */
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
        return nativeImageBinary(home) != null ? new Graal.Ok(home) : new Graal.Fail(NATIVE_IMAGE_MISSING);
    }

    /**
     * {@code --main} / {@code [native].main-class} / {@code [image].main} / {@code [application]
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
    public static String specifiedMain(Path moduleDir, String cliOverride) {
        if (notBlank(cliOverride)) return cliOverride;
        Path toml = moduleDir.resolve("jk.toml");
        if (!Files.isRegularFile(toml)) return null;
        var scan = TomlScan.scan(toml, "native.main-class", "image.main", "application.main");
        String fromNative = scan.get("native.main-class");
        if (notBlank(fromNative)) return fromNative;
        String fromImage = scan.get("image.main");
        if (notBlank(fromImage)) return fromImage;
        String fromApp = scan.get("application.main");
        return notBlank(fromApp) ? fromApp : null;
    }

    static Path nativeImageBinary(Path graalHome) {
        Path bin = graalHome.resolve("bin");
        for (String name : List.of("native-image", "native-image.cmd", "native-image.exe")) {
            Path p = bin.resolve(name);
            if (Files.isRegularFile(p)) return p;
        }
        return null;
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
        Path target =
                ws.equals(abs) ? abs.resolve("target") : ws.resolve("target").resolve(ws.relativize(abs));
        return target.resolve("classes").resolve("main");
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

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
