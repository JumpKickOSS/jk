// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.minified;

import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.DeterministicZip;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.plugin.build.PackageIo;
import cc.jumpkick.plugin.build.TaskExec;
import cc.jumpkick.surface.DynamicSurface;
import cc.jumpkick.surface.DynamicSurfaceIo;
import cc.jumpkick.surface.KeepRuleEmitter;
import cc.jumpkick.surface.TrainLayout;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.SignatureAttribute;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import org.jspecify.annotations.Nullable;

/**
 * {@code minified-jar} packager: R8 {@code --classfile} full mode over classes + runtime closure →
 * one slim jar. Shrink-only by default; {@code obfuscate = true} also emits the {@code -mapping.txt}
 * deobfuscation map beside the jar, as a declared output so a cache hit restores the pair.
 *
 * <p>{@code [application] main} is optional: when present, R8 keeps the entry point and the
 * manifest gets {@code Main-Class}. When absent (library fat/shrunk jars), every class from the
 * module's own classes dir is kept so R8 can still strip unused dependency code.
 */
final class MinifiedJarPackager {

    private static final DeterministicZip ZIP = DeterministicZip.PINNED;

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
            // R8 judges program inputs by extension — alias any extensionless path as .jar.
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
            DynamicSurface composed = ByNameIndex.composedFromLibraries(program);
            DynamicSurface surface = ByNameIndex.surface(derived).merge(composed);
            // Optional train observations from `jk train` (target/train/merged/dynamic-surface.json).
            Path trainSurface = io.artifactPath()
                    .resolveSibling(TrainLayout.ROOT)
                    .resolve("merged")
                    .resolve(TrainLayout.SURFACE_JSON);
            DynamicSurface trained = DynamicSurface.empty();
            if (Files.isRegularFile(trainSurface)) {
                trained = DynamicSurfaceIo.readJson(trainSurface);
                surface = surface.merge(trained);
            }
            if (!surface.entries().isEmpty()) {
                pro.append("\n# Derived from by-name indexes, library native-image metadata")
                        .append(trained.entries().isEmpty() ? "" : ", and train observations")
                        .append(".\n")
                        .append(KeepRuleEmitter.emit(surface));
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
            // The deobfuscation map. Obfuscated stack traces are unreadable without it, and it is
            // the only copy — so it is a declared output, cached and restored with the jar. A
            // cache hit that brought back the jar alone would leave a shipped artifact whose crash
            // reports can never be resolved again.
            Path mapping = null;
            if (obfuscate) {
                mapping = io.artifactPath().resolveSibling(stripExtension(io.artifactPath()) + "-mapping.txt");
                run.arg("--pg-map-output").arg(mapping.toString());
            }
            long before = 0;
            for (Path p : program) before += Files.size(p);
            io.label("R8 shrink (" + program.size() + " inputs, " + mb(before) + ")");
            for (Path p : program) run.arg(p.toString());
            TaskExec.ToolRun.Result result = run.run();
            if (result.exit() != 0) {
                throw new IllegalStateException("R8 failed (exit " + result.exit() + "):\n" + result.output());
            }
            if (mapping != null) io.produced(mapping);
            int absent = ByNameIndex.countMissingClasses(result.output());
            if (absent > 0) {
                io.label(absent + " optional " + (absent == 1 ? "class is" : "classes are")
                        + " absent from the closure — run with -v to list them, or set"
                        + " [minified] strict-warnings = true to fail on them");
            }

            auditByNameIndexes(program, shrunk);
            SignatureAudit signatures = auditGenericSignatures(program, shrunk);
            if (!signatures.degraded().isEmpty()) {
                // A warning, never a failure (and deliberately outside strict-warnings): plenty of
                // applications do no runtime generic matching, and unlike the by-name audit the
                // jar carries no evidence the signatures are needed.
                io.label(signatureWarning(signatures));
            }
            writeOutputJar(shrunk, io.artifactPath(), mainClass);
            io.label("shrunk " + mb(before) + " → " + mb(Files.size(io.artifactPath())));
        } finally {
            PathUtil.deleteRecursively(work);
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
                .append(KeepRuleEmitter.emit(
                        ByNameIndex.surface(expected.stream().limit(3).toList())));
        if (expected.size() > 3) message.append("  …\n");
        throw new IllegalStateException(message.toString());
    }

    /** One class's generic-signature drift: what the inputs carried, what the output kept. */
    record SignatureDrift(
            String className, String before, @Nullable String after) {}

    /** {@code compared} = classes present in both inputs and output that carried a signature. */
    record SignatureAudit(int compared, List<SignatureDrift> degraded) {}

    /**
     * Warn when R8 erased or raw-ified class-level generic {@code Signature} attributes.
     *
     * <p>{@code -keepattributes Signature} is passed, and is not enough: in {@code --classfile}
     * full mode a class keeps its signature only when it is explicitly kept along with the types
     * the signature references. A framework that resolves beans by runtime generic matching then
     * fails at startup with {@code No bean of type [Foo<Bar>]} and no hint that packaging caused
     * it. Measured on a Micronaut jar: 1,799 of 5,550 shared classes (32%) lost theirs.
     *
     * <p>The removal class of damage is {@link #auditByNameIndexes}'s and fails the build; this
     * is the rewriting class, and it stays a warning — nothing in the jar proves the signatures
     * are needed.
     */
    // Package-private for MinifiedJarAuditTest.
    static SignatureAudit auditGenericSignatures(List<Path> program, Path shrunk) throws IOException {
        Map<String, String> before = classSignatures(program);
        Map<String, String> after = classSignatures(List.of(shrunk));
        int compared = 0;
        List<SignatureDrift> degraded = new ArrayList<>();
        for (Map.Entry<String, String> e : before.entrySet()) {
            if (e.getValue() == null || !after.containsKey(e.getKey())) continue;
            compared++;
            String kept = after.get(e.getKey());
            if (!e.getValue().equals(kept)) {
                degraded.add(new SignatureDrift(e.getKey(), e.getValue(), kept));
            }
        }
        degraded.sort(Comparator.comparing(SignatureDrift::className));
        return new SignatureAudit(compared, List.copyOf(degraded));
    }

    /** Past this share, keep rules cannot help and the honest advice is a fat jar. */
    private static final int ASSEMBLY_ADVICE_PERCENT = 5;

    // Package-private for MinifiedJarAuditTest.
    static String signatureWarning(SignatureAudit audit) {
        int n = audit.degraded().size();
        StringBuilder sb = new StringBuilder();
        sb.append(n)
                .append(" of ")
                .append(audit.compared())
                .append(" classes lost or raw-ified their generic Signature (e.g. ");
        for (int i = 0; i < Math.min(3, n); i++) {
            SignatureDrift d = audit.degraded().get(i);
            if (i > 0) sb.append("; ");
            sb.append(d.className())
                    .append(" was `")
                    .append(d.before())
                    .append("`, now ")
                    .append(d.after() == null ? "none" : "`" + d.after() + "`");
        }
        sb.append("). -keepattributes Signature is not enough in classfile full mode — a class"
                + " keeps its signature only when it and the types the signature references are"
                + " explicitly kept. Anything resolving types by runtime generic matching will"
                + " fail at startup. ");
        if (n * 100L >= (long) audit.compared() * ASSEMBLY_ADVICE_PERCENT) {
            sb.append("At this scale keep rules cannot restore them: this application is not a"
                    + " minify candidate — use [application] assembly = true.");
        } else {
            sb.append("Keep the referenced types with [minified] keep to restore them.");
        }
        return sb.toString();
    }

    /**
     * Class-level {@code Signature} per class ({@code null} when the class carries none). First
     * occurrence wins, matching the classpath's shadowing rule. Malformed class files have
     * nothing to compare and are skipped.
     */
    private static Map<String, String> classSignatures(Collection<Path> jars) throws IOException {
        Map<String, String> out = new HashMap<>();
        for (Path jar : jars) {
            if (!Files.isRegularFile(jar)) continue;
            try (JarFile jf = new JarFile(jar.toFile())) {
                Enumeration<JarEntry> entries = jf.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory() || !name.endsWith(".class") || name.endsWith("module-info.class")) {
                        continue;
                    }
                    String fqcn =
                            name.substring(0, name.length() - ".class".length()).replace('/', '.');
                    if (out.containsKey(fqcn)) continue;
                    try (InputStream in = jf.getInputStream(entry)) {
                        String signature = null;
                        for (var attr : ClassFile.of().parse(in.readAllBytes()).attributes()) {
                            if (attr instanceof SignatureAttribute sig) {
                                signature = sig.signature().stringValue();
                                break;
                            }
                        }
                        out.put(fqcn, signature);
                    } catch (RuntimeException malformed) {
                        // not a parseable class file — nothing to compare
                    }
                }
            }
        }
        return out;
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
            // Free test first: the walk already paid for this entry, and isRegularFile re-resolves
            // the path for a fresh stat even for entries the name test discards.
            List<Path> classes = walk.filter(p -> p.getFileName().toString().endsWith(".class"))
                    .filter(p -> !p.getFileName().toString().equals("module-info.class"))
                    .filter(Files::isRegularFile)
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

    /**
     * The module's classes as one R8 program input. Compile freshness stamps are left out: R8
     * copies unrecognised inputs straight through, so a stamp taken in here would ship in the
     * shrunk jar with a wall clock inside it.
     */
    // Package-private for MinifiedJarPackagerTest.
    static void zipClasses(Path classesDir, Path jar) throws IOException {
        try (OutputStream out = DeterministicZip.archiveStream(jar);
                JarOutputStream jos = new JarOutputStream(out);
                Stream<Path> walk = Files.walk(classesDir)) {
            List<Path> files = walk.filter(Files::isRegularFile)
                    .sorted(Comparator.naturalOrder())
                    .toList();
            for (Path file : files) {
                String name = classesDir.relativize(file).toString().replace('\\', '/');
                if (BuildStamps.isStampFile(name)) continue;
                ZIP.writeEntry(jos, name, file);
            }
        }
    }

    /**
     * R8's output jar, rewritten with a deterministic order/times. Sets {@code Main-Class} only when
     * {@code mainClass} is non-null (library fat/shrunk jars need no entry point).
     *
     * <p>R8's output carries no directory entries; {@link DeterministicZip#writeParentDirs}
     * synthesizes them, because frameworks that enumerate resource directories from the classpath
     * resolve them that way.
     */
    // Package-private for MinifiedJarPackagerTest.
    static void writeOutputJar(Path shrunk, Path artifact, @Nullable String mainClass) throws IOException {
        Files.createDirectories(artifact.getParent());
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (mainClass != null && !mainClass.isBlank()) {
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
        }
        try (JarFile in = new JarFile(shrunk.toFile());
                OutputStream out = DeterministicZip.archiveStream(artifact);
                JarOutputStream jos = new JarOutputStream(out)) {
            Set<String> dirs = new HashSet<>();
            ZIP.writeParentDirs(jos, JarFile.MANIFEST_NAME, dirs);
            ZIP.writeManifest(jos, manifest);
            List<JarEntry> entries = new ArrayList<>();
            for (Enumeration<JarEntry> e = in.entries(); e.hasMoreElements(); ) {
                entries.add(e.nextElement());
            }
            entries.sort(Comparator.comparing(ZipEntry::getName));
            for (JarEntry entry : entries) {
                if (entry.isDirectory() || entry.getName().equals(JarFile.MANIFEST_NAME)) continue;
                ZIP.writeParentDirs(jos, entry.getName(), dirs);
                ZIP.writeEntryStreaming(jos, entry.getName(), in.getInputStream(entry));
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
}
