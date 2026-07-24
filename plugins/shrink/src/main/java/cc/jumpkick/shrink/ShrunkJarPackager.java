// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.shrink;

import cc.jumpkick.plugin.build.PackageIo;
import cc.jumpkick.plugin.build.StepExec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

/**
 * {@code shrunk-jar} packager: R8 {@code --classfile} full mode over classes + runtime closure →
 * one slim jar. Shrink-only by default; {@code obfuscate = true} writes a mapping file.
 *
 * <p>{@code [application] main} is optional: when present, R8 keeps the entry point and the
 * manifest gets {@code Main-Class}. When absent (library fat/shrunk jars), every class from the
 * module's own classes dir is kept so R8 can still strip unused dependency code.
 */
final class ShrunkJarPackager {

    /** Fixed entry timestamp (zip's floor is 1980) — reproducible output, same as jk's packagers. */
    private static final long ENTRY_MILLIS = java.time.LocalDateTime.of(1980, 2, 1, 0, 0)
            .toInstant(java.time.ZoneOffset.UTC)
            .toEpochMilli();

    private ShrunkJarPackager() {}

    static void produce(PackageIo io) throws Exception {
        String mainClass = io.project().mainClass();
        if (mainClass != null && mainClass.isBlank()) mainClass = null;
        Path r8 = io.extra("r8")
                .orElseThrow(() -> new IllegalStateException("the r8 packager-dependency was not supplied"));
        Path work = Files.createTempDirectory("jk-shrink-");
        try {
            // R8's program inputs: the module classes (zipped — one input shape) + runtime jars.
            Path classesJar = work.resolve("classes.jar");
            zipClasses(io.classesDir(), classesJar);
            List<Path> program = new ArrayList<>();
            program.add(classesJar);
            for (PackageIo.RuntimeEntry entry : io.runtimeEntries()) {
                if (entry.jar() != null) program.add(entry.jar());
            }

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
            for (String rule : io.config().stringList("keep")) {
                pro.append(rule).append('\n');
            }
            Files.writeString(rules, pro);

            Path shrunk = work.resolve("shrunk.jar");
            StepExec.ToolRun run = io.java()
                    .classpath(List.of(r8))
                    .mainClass("com.android.tools.r8.R8")
                    .arg("--release")
                    .arg("--classfile")
                    .arg("--output")
                    .arg(shrunk.toString())
                    .arg("--lib")
                    .arg(io.javaHome().toString())
                    .arg("--pg-conf")
                    .arg(rules.toString());
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
            StepExec.ToolRun.Result result = run.run();
            if (result.exit() != 0) {
                throw new IllegalStateException("R8 failed (exit " + result.exit() + "):\n" + result.output());
            }

            writeOutputJar(shrunk, io.artifactPath(), mainClass);
            io.label("shrunk " + mb(before) + " → " + mb(Files.size(io.artifactPath())));
        } finally {
            deleteRecursively(work);
        }
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
            for (Path file : classes) {
                String name = classesDir.relativize(file).toString().replace('\\', '/');
                if (name.contains("$")) continue; // outer-class keep retains nested types
                String fqcn = name.substring(0, name.length() - ".class".length()).replace('/', '.');
                pro.append("-keep class ").append(fqcn).append(" { *; }\n");
                kept++;
            }
        }
        if (kept == 0) pro.append("-dontshrink\n");
    }

    /** A project-relative path for a declared keep-file. */
    private static Path projectFile(PackageIo io, String rel) {
        Path file = io.moduleDir().resolve(rel);
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("[shrink] keep-files entry not found: " + rel);
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
                JarEntry entry = new JarEntry(name);
                entry.setTime(ENTRY_MILLIS);
                jos.putNextEntry(entry);
                Files.copy(file, jos);
                jos.closeEntry();
            }
        }
    }

    /**
     * R8's output jar, rewritten with a deterministic order/times. Sets {@code Main-Class} only when
     * {@code mainClass} is non-null (library fat/shrunk jars need no entry point).
     */
    private static void writeOutputJar(Path shrunk, Path artifact, String mainClass) throws IOException {
        Files.createDirectories(artifact.getParent());
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (mainClass != null && !mainClass.isBlank()) {
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
        }
        try (JarFile in = new JarFile(shrunk.toFile());
                OutputStream out = Files.newOutputStream(artifact);
                JarOutputStream jos = new JarOutputStream(out)) {
            JarEntry manifestEntry = new JarEntry("META-INF/MANIFEST.MF");
            manifestEntry.setTime(ENTRY_MILLIS);
            jos.putNextEntry(manifestEntry);
            manifest.write(jos);
            jos.closeEntry();
            List<JarEntry> entries = new ArrayList<>();
            for (Enumeration<JarEntry> e = in.entries(); e.hasMoreElements(); ) {
                entries.add(e.nextElement());
            }
            entries.sort(Comparator.comparing(ZipEntry::getName));
            for (JarEntry entry : entries) {
                if (entry.isDirectory() || entry.getName().equals("META-INF/MANIFEST.MF")) continue;
                JarEntry copy = new JarEntry(entry.getName());
                copy.setTime(ENTRY_MILLIS);
                jos.putNextEntry(copy);
                try (InputStream body = in.getInputStream(entry)) {
                    body.transferTo(jos);
                }
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
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1_000_000.0);
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
