// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.minified;

import cc.jumpkick.plugin.build.PackageIo;
import cc.jumpkick.plugin.build.TaskExec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

/**
 * {@code minified-jar} packager: R8 {@code --classfile} full mode over classes + runtime closure →
 * one slim jar. Shrink-only by default; {@code obfuscate = true} writes a mapping file.
 *
 * <p>{@code [application] main} is optional: when present, R8 keeps the entry point and the
 * manifest gets {@code Main-Class}. When absent (library fat/shrunk jars), every class from the
 * module's own classes dir is kept so R8 can still strip unused dependency code.
 */
final class MinifiedJarPackager {

    /** Fixed entry timestamp (zip's floor is 1980) — reproducible output, same as jk's packagers. */
    private static final LocalDateTime ENTRY_TIME = LocalDateTime.of(1980, 2, 1, 0, 0);

    /**
     * A pinned-time entry via {@link JarEntry#setTimeLocal} — NOT {@code setTime}, whose DOS-time
     * conversion is timezone-sensitive and would make the bytes (and raw-jar fingerprints) vary
     * with the build host's $TZ. Mirrors the engine's DeterministicJar.
     */
    private static JarEntry pinnedEntry(String name) {
        JarEntry entry = new JarEntry(name);
        entry.setTimeLocal(ENTRY_TIME);
        return entry;
    }

    private MinifiedJarPackager() {}

    /** Alias every extensionless jar as {@code .jar} under {@code work} (R8 judges by extension). */
    private static List<Path> jarSuffixed(Path work, List<Path> jars) throws IOException {
        List<Path> out = new ArrayList<>(jars.size());
        Path dir = null;
        int i = 0;
        for (Path jar : jars) {
            String name = jar.getFileName().toString();
            if (name.endsWith(".jar") || name.endsWith(".zip")) {
                out.add(jar);
            } else {
                if (dir == null) dir = Files.createDirectories(work.resolve("rt-jars"));
                Path alias = dir.resolve("rt-" + i + "-" + name + ".jar");
                try {
                    Files.createLink(alias, jar);
                } catch (IOException | UnsupportedOperationException e) {
                    Files.copy(jar, alias);
                }
                out.add(alias);
            }
            i++;
        }
        return out;
    }

    static void produce(PackageIo io) throws Exception {
        String mainClass = io.project().mainClass();
        if (mainClass != null && mainClass.isBlank()) mainClass = null;
        Path r8 = io.extra("r8")
                .orElseThrow(() -> new IllegalStateException("the r8 packager-dependency was not supplied"));
        Path work = Files.createTempDirectory("jk-minified-");
        try {
            // R8's program inputs: the module classes (zipped — one input shape) + runtime jars.
            Path classesJar = work.resolve("classes.jar");
            zipClasses(io.classesDir(), classesJar);
            List<Path> program = new ArrayList<>();
            program.add(classesJar);
            for (PackageIo.RuntimeEntry entry : io.runtimeEntries()) {
                if (entry.jar() != null) program.add(entry.jar());
            }
            // R8 judges program inputs by extension; store-materialized runtime jars are
            // extensionless CAS blobs — alias them as .jar before they reach the tool (JK-1449).
            program = jarSuffixed(work, program);

            boolean obfuscate = io.config().bool("obfuscate", false);
            Path rules = work.resolve("keep.pro");
            StringBuilder pro = new StringBuilder();
            if (mainClass != null) {
                pro.append("-keep class ")
                        .append(mainClass)
                        .append(" { public static void main(java.lang.String[]); }\n");
            } else {
                // Library / no entry point: keep the module's own classes so R8 still shrinks
                // unused dependency code without requiring [application] main.
                appendModuleClassKeeps(pro, io.classesDir());
            }
            pro.append("-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,")
                    .append("SourceFile,LineNumberTable\n");
            if (!obfuscate) pro.append("-dontobfuscate\n");

            // Classes the inputs name by text rather than reference. The indexes enumerate them
            // exactly, so deriving the rules beats any pattern a user could write: it covers
            // hand-written framework internals that match no naming convention, and it tracks
            // whatever the project actually depends on.
            Set<String> derived = new TreeSet<>(ByNameIndex.referencedClasses(program));
            derived.retainAll(ByNameIndex.classesIn(program));

            // Libraries describe their own reflective surface in META-INF/native-image for
            // native-image, which reads it unaided. R8 has no equivalent, so the same facts reach
            // it as keep rules. Free: the data is already in the jars, no run involved.
            cc.jumpkick.surface.DynamicSurface composed = ByNameIndex.composedFromLibraries(program);
            cc.jumpkick.surface.DynamicSurface surface =
                    ByNameIndex.surface(derived).merge(composed);
            // Optional train observations from `jk train` (target/train/merged/dynamic-surface.json).
            Path trainSurface = io.artifactPath()
                    .getParent()
                    .resolve(cc.jumpkick.surface.TrainLayout.ROOT)
                    .resolve("merged")
                    .resolve(cc.jumpkick.surface.TrainLayout.SURFACE_JSON);
            cc.jumpkick.surface.DynamicSurface trained = cc.jumpkick.surface.DynamicSurface.empty();
            if (Files.isRegularFile(trainSurface)) {
                trained = cc.jumpkick.surface.DynamicSurfaceIo.readJson(trainSurface);
                surface = surface.merge(trained);
            }
            if (!surface.entries().isEmpty()) {
                pro.append("\n# Derived from by-name indexes, library native-image metadata")
                        .append(trained.entries().isEmpty() ? "" : ", and train observations")
                        .append(".\n")
                        .append(cc.jumpkick.surface.KeepRuleEmitter.emit(surface));
                io.label("keep rules: " + derived.size() + " from by-name indexes, "
                        + composed.entries().size() + " from library metadata"
                        + (trained.entries().isEmpty()
                                ? ""
                                : ", " + trained.entries().size() + " from train"));
            }

            for (String rule : io.config().stringList("keep")) {
                pro.append(rule).append('\n');
            }
            Files.writeString(rules, pro);
            // The effective rule set, next to the artifact: the one place to look when R8 kept
            // something unexpected, or when writing a rule to cover what it could not derive.
            Path effectiveRules = io.artifactPath().resolveSibling(stripExtension(io.artifactPath()) + "-keep.pro");
            Files.copy(rules, effectiveRules, StandardCopyOption.REPLACE_EXISTING);
            io.produced(effectiveRules);

            Path shrunk = work.resolve("shrunk.jar");
            TaskExec.ToolRun run = io.java()
                    .classpath(List.of(r8))
                    .mainClass("com.android.tools.r8.R8")
                    .arg("--release")
                    // Desugaring rewrites for an Android API level. This output is a JVM jar, and
                    // the rewrite is not merely pointless here: it emits invokespecial to an
                    // interface default method that is not a direct superinterface, which the
                    // verifier rejects outright (VerifyError at first use).
                    .arg("--no-desugaring")
                    .arg("--classfile")
                    .arg("--output")
                    .arg(shrunk.toString())
                    .arg("--lib")
                    .arg(io.javaHome().toString())
                    .arg("--pg-conf")
                    .arg(rules.toString());
            // jk resolved this closure from the lockfile, so a class missing from it is absent on
            // purpose — an optional dependency behind a Class.forName probe. Netty and Micronaut
            // alone contribute dozens. R8 calls that an error and produces nothing, so downgrade
            // just that diagnostic; the messages still print, and `strict-warnings` restores the
            // hard failure for closures that should be complete.
            if (!io.config().bool("strict-warnings", false)) {
                run.arg("--map-diagnostics:MissingDefinitionsDiagnostic")
                        .arg("error")
                        .arg("warning");
            }
            // Project rule files ride as further --pg-conf entries (already declared inputs).
            for (String rel : io.config().stringList("keep-files")) {
                run.arg("--pg-conf").arg(projectFile(io, rel).toString());
            }
            if (obfuscate) {
                run.arg("--pg-map-output")
                        .arg(io.artifactPath()
                                .resolveSibling(stripExtension(io.artifactPath()) + "-mapping.txt")
                                .toString());
            }
            long before = 0;
            for (Path p : program) before += Files.size(p);
            io.label("R8 shrink (" + program.size() + " inputs, " + mb(before) + ")");
            for (Path p : program) run.arg(p.toString());
            TaskExec.ToolRun.Result result = run.run();
            if (result.exit() != 0) {
                throw new IllegalStateException("R8 failed (exit " + result.exit() + "):\n" + result.output());
            }
            int absent = ByNameIndex.countMissingClasses(result.output());
            if (absent > 0) {
                io.label(absent + " optional " + (absent == 1 ? "class is" : "classes are")
                        + " absent from the closure — run with -v to list them, or set"
                        + " [minified] strict-warnings = true to fail on them");
            }

            auditByNameIndexes(program, shrunk);
            writeOutputJar(shrunk, io.artifactPath(), mainClass);
            io.label("shrunk " + mb(before) + " → " + mb(Files.size(io.artifactPath())));
        } finally {
            deleteRecursively(work);
        }
    }

    /**
     * Fail when R8 removed a class that a service file or marker index still names.
     *
     * <p>Such a class is unreachable to static analysis and is loaded by name at runtime, so
     * removing it does not produce a link error — the loader (Micronaut's {@code
     * SoftServiceLoader}, {@code java.util.ServiceLoader}) skips what it cannot load, and the
     * application starts missing pieces. Losing an SLF4J provider this way silences the very
     * logging that would report the damage. A build error naming the classes is the only place
     * this is cheap to catch.
     *
     * <p>Scoped to classes the inputs actually carried: a name an input already failed to resolve
     * belongs to an optional dependency nobody bundled, and is not R8's doing.
     */
    // Package-private for MinifiedJarAuditTest.
    static void auditByNameIndexes(List<Path> program, Path shrunk) throws IOException {
        Set<String> expected = new TreeSet<>(ByNameIndex.referencedClasses(program));
        expected.retainAll(ByNameIndex.classesIn(program));
        expected.removeAll(ByNameIndex.classesIn(List.of(shrunk)));
        if (expected.isEmpty()) return;

        StringBuilder message = new StringBuilder("R8 removed ")
                .append(expected.size())
                .append(expected.size() == 1 ? " class that is" : " classes that are")
                .append(" named by a service file or index in this jar, so nothing can load ")
                .append(expected.size() == 1 ? "it" : "them")
                .append(" at runtime:\n");
        int shown = 0;
        for (String name : expected) {
            if (shown++ == 20) {
                message.append("  … and ").append(expected.size() - 20).append(" more\n");
                break;
            }
            message.append("  ").append(name).append('\n');
        }
        message.append("\nKeep them with [minified] keep, or a keep-files rule file:\n")
                .append(cc.jumpkick.surface.KeepRuleEmitter.emit(
                        ByNameIndex.surface(expected.stream().limit(3).toList())));
        if (expected.size() > 3) message.append("  …\n");
        throw new IllegalStateException(message.toString());
    }

    /**
     * Keep every top-level class compiled for this module. Inner classes are covered by their
     * outer keep when present; anonymous/local types stay reachable from kept members.
     */
    static void appendModuleClassKeeps(StringBuilder pro, Path classesDir) throws IOException {
        if (classesDir == null || !Files.isDirectory(classesDir)) {
            // Empty module: still need a rule so R8 does not delete the whole program graph.
            pro.append("-dontshrink\n");
            return;
        }
        int kept = 0;
        try (Stream<Path> walk = Files.walk(classesDir)) {
            List<Path> classes = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".class"))
                    .filter(p -> !p.getFileName().toString().equals("module-info.class"))
                    .sorted(Comparator.naturalOrder())
                    .toList();
            // Relative paths present in the tree (for outer-class peer checks).
            Set<String> classPaths = new HashSet<>();
            for (Path file : classes) {
                classPaths.add(classesDir.relativize(file).toString().replace('\\', '/'));
            }
            for (Path file : classes) {
                String name = classesDir.relativize(file).toString().replace('\\', '/');
                if (isNestedClassFile(name, classPaths)) continue; // outer keep retains nested types
                String fqcn =
                        name.substring(0, name.length() - ".class".length()).replace('/', '.');
                pro.append("-keep class ").append(fqcn).append(" { *; }\n");
                kept++;
            }
        }
        if (kept == 0) pro.append("-dontshrink\n");
    }

    /**
     * True when {@code relClassPath} is a javac nested type ({@code Outer$Inner.class}) whose
     * outer {@code Outer.class} is also present. Top-level names that merely contain {@code $}
     * (legal on the JVM) have no outer peer and must still get a keep rule.
     */
    static boolean isNestedClassFile(String relClassPath, Set<String> classPaths) {
        String fileName = relClassPath;
        int slash = relClassPath.lastIndexOf('/');
        if (slash >= 0) fileName = relClassPath.substring(slash + 1);
        if (!fileName.endsWith(".class") || !fileName.contains("$")) return false;
        String beforeDollar = fileName.substring(0, fileName.indexOf('$'));
        if (beforeDollar.isEmpty()) return false;
        String outerRel = (slash >= 0 ? relClassPath.substring(0, slash + 1) : "") + beforeDollar + ".class";
        return classPaths.contains(outerRel);
    }

    /** A project-relative path for a declared keep-file. */
    private static Path projectFile(PackageIo io, String rel) {
        Path file = io.moduleDir().resolve(rel);
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("[minified] keep-files entry not found: " + rel);
        }
        return file;
    }

    private static void zipClasses(Path classesDir, Path jar) throws IOException {
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream jos = new JarOutputStream(out);
                Stream<Path> walk = Files.walk(classesDir)) {
            List<Path> files = walk.filter(Files::isRegularFile)
                    .sorted(Comparator.naturalOrder())
                    .toList();
            for (Path file : files) {
                String name = classesDir.relativize(file).toString().replace('\\', '/');
                jos.putNextEntry(pinnedEntry(name));
                Files.copy(file, jos);
                jos.closeEntry();
            }
        }
    }

    /**
     * R8's output jar, rewritten with a deterministic order/times. Sets {@code Main-Class} only when
     * {@code mainClass} is non-null (library fat/shrunk jars need no entry point).
     *
     * <p>Parent directory entries are synthesized for every file: frameworks that enumerate
     * resource directories from the classpath (Micronaut's SoftServiceLoader over {@code
     * META-INF/micronaut/...}) resolve them via the jar's directory entries, and R8's output
     * carries none. Thin, fat, and minified jars owe the same contract (JK-1414/JK-1667/JK-1755).
     */
    // Package-private for MinifiedJarPackagerTest.
    static void writeOutputJar(Path shrunk, Path artifact, String mainClass) throws IOException {
        Files.createDirectories(artifact.getParent());
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (mainClass != null && !mainClass.isBlank()) {
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
        }
        try (JarFile in = new JarFile(shrunk.toFile());
                OutputStream out = Files.newOutputStream(artifact);
                JarOutputStream jos = new JarOutputStream(out)) {
            Set<String> dirs = new HashSet<>();
            writeParentDirs(jos, "META-INF/MANIFEST.MF", dirs);
            jos.putNextEntry(pinnedEntry("META-INF/MANIFEST.MF"));
            manifest.write(jos);
            jos.closeEntry();
            List<JarEntry> entries = new ArrayList<>();
            for (Enumeration<JarEntry> e = in.entries(); e.hasMoreElements(); ) {
                entries.add(e.nextElement());
            }
            entries.sort(Comparator.comparing(ZipEntry::getName));
            for (JarEntry entry : entries) {
                if (entry.isDirectory() || entry.getName().equals("META-INF/MANIFEST.MF")) continue;
                writeParentDirs(jos, entry.getName(), dirs);
                jos.putNextEntry(pinnedEntry(entry.getName()));
                try (InputStream body = in.getInputStream(entry)) {
                    body.transferTo(jos);
                }
                jos.closeEntry();
            }
        }
    }

    /**
     * Directory entries for every ancestor of {@code name}, parents first, each once —
     * {@code dirs} accumulates what has already been emitted across the whole jar. Local copy of
     * the engine's DeterministicJar.writeParentDirs by design: plugins stay dependency-free of
     * jk's kernel modules.
     */
    private static void writeParentDirs(JarOutputStream jos, String name, Set<String> dirs) throws IOException {
        int slash = -1;
        while ((slash = name.indexOf('/', slash + 1)) >= 0) {
            String dir = name.substring(0, slash + 1);
            if (dirs.add(dir)) {
                jos.putNextEntry(pinnedEntry(dir));
                jos.closeEntry();
            }
        }
    }

    private static String stripExtension(Path artifact) {
        String name = artifact.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0);
    }

    // Local copy by design: plugins stay dependency-free of jk's kernel modules.
    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    /* best-effort temp cleanup */
                }
            });
        }
    }
}
