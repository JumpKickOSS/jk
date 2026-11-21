// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.plugin.build.TaskExec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * {@code android-res} step: aapt2 compile+link → {@code resources.ap_}, {@code R.txt}, {@code
 * R.java}, {@code raw-res/}. The AAR resources are merged ({@link ResourceMerger}) and compiled by
 * {@code android-res-deps} ({@link #compileDeps(TaskExec)}), a step keyed by the dependency set alone;
 * module resources overlay them. Non-transitive R: apps regenerate each AAR's R with final ids;
 * libraries ship {@code R.txt}. The link's resource ids land in {@code packaged/ids.txt} and the
 * module's compiled resources in {@code packaged/res-compiled.zip}, so {@link #linkUnitTestApk}
 * keeps the ids and compiles nothing of the module's again.
 */
final class ResourceStep {

    /** The step that compiles the production closure's AAR resources. */
    static final String DEPS_STEP = "android-res-deps";

    /** {@link #DEPS_STEP}'s output dir. */
    static final String DEPS_OUT = "compiled";

    /** The compiled AAR resources under {@link #DEPS_OUT}; absent when no AAR carries resources. */
    private static final String DEPS_FLATS = "deps-compiled.zip";

    /** The AAR {@code res/} dirs merged into {@link #DEPS_FLATS}, one absolute path per line, in merge order. */
    private static final String DEPS_DIRS = "deps-res-dirs.txt";

    /** The main link's {@code --emit-ids} file under {@code packaged/}: one {@code name = id} per line. */
    private static final String IDS = "ids.txt";

    /** The module's own compiled resources under {@code packaged/}; absent when it has no {@code res/}. */
    private static final String OWN_FLATS = "res-compiled.zip";

    private ResourceStep() {}

    /**
     * The {@code android-res-deps} step: every AAR's resources on the production runtime closure,
     * merged and compiled once into {@link #DEPS_FLATS}, with the dirs it merged in {@link #DEPS_DIRS}.
     */
    static void compileDeps(TaskExec exec) throws Exception {
        List<AndroidDeps.Aar> aars = AndroidDeps.aars(exec.runtimeEntries());
        Path out = exec.outputDir(DEPS_OUT);
        List<String> dirs = resDirs(aars).stream().map(Path::toString).toList();
        Files.write(out.resolve(DEPS_DIRS), dirs);
        if (dirs.isEmpty()) return;
        compileMergedDeps(exec, extractAapt2(exec), aars, exec.privateDir("work"), out.resolve(DEPS_FLATS));
    }

    static void run(TaskExec exec) throws Exception {
        Path aapt2 = extractAapt2(exec);
        Path platformJar = exec.requireExtra("android-jar");
        Path res = AndroidDeps.androidFile(exec.moduleDir(), "res");
        Path manifest = exec.requireStepOutput("android-manifest").resolve("merged/AndroidManifest.xml");
        if (!Files.isRegularFile(manifest)) {
            throw new IllegalStateException("android-manifest produced no merged manifest at " + manifest);
        }
        String namespace = exec.config().string("namespace");
        boolean library = exec.config().bool("library", false);
        long compileSdk = exec.config().intValue("compile-sdk", 0);
        long minSdk = exec.config().intValue("min-sdk", 0);
        List<AndroidDeps.Aar> aars = AndroidDeps.aars(exec.runtimeEntries());

        Path gen = exec.outputDir("gen");
        Path packaged = exec.outputDir("packaged");
        Path rawRes = exec.outputDir("raw-res");

        Path depFlats = depsFlats(exec.requireStepOutput(DEPS_STEP));
        Path ownFlats = null;
        if (Files.isDirectory(res)) {
            exec.label("aapt2 compile");
            ownFlats = compileRes(exec, aapt2, res, packaged.resolve(OWN_FLATS));
            copyTree(res, rawRes);
        }

        // aapt2 link: deps as plain inputs (classpath order), the module's own resources as the
        // overlay — one binary package, one merged symbol table, the module's R.java, and the
        // generated keep rules (manifest components) R8 consumes on release.
        exec.label("aapt2 link");
        Path rTxt = packaged.resolve("R.txt");
        TaskExec.ToolRun.Result linked = link(
                        exec,
                        aapt2,
                        platformJar,
                        manifest,
                        namespace,
                        compileSdk,
                        minSdk,
                        library,
                        depFlats,
                        ownFlats,
                        packaged.resolve("resources.ap_"),
                        gen,
                        rTxt,
                        packaged.resolve("keep-rules.pro"),
                        false)
                .arg("--emit-ids")
                .arg(packaged.resolve(IDS).toAbsolutePath().toString())
                .run();
        if (linked.exit() != 0) {
            throw new IllegalStateException("aapt2 link failed:\n" + linked.output());
        }

        // Release app: a second link in proto format — the AAB's resource table (bundletool
        // consumes protobuf resources, never the binary arsc).
        boolean release = "release".equals(exec.config().stringOpt("build-type").orElse("debug"));
        if (release && !library) {
            exec.label("aapt2 link (proto)");
            TaskExec.ToolRun.Result proto = link(
                            exec,
                            aapt2,
                            platformJar,
                            manifest,
                            namespace,
                            compileSdk,
                            minSdk,
                            false,
                            depFlats,
                            ownFlats,
                            packaged.resolve("resources-proto.ap_"),
                            null,
                            null,
                            null,
                            true)
                    .run();
            if (proto.exit() != 0) {
                throw new IllegalStateException("aapt2 link --proto-format failed:\n" + proto.output());
            }
        }

        // Non-transitive R: regenerate each dependency's R class from ITS R.txt with the ids
        // this link assigned — final ids in an app link, placeholders in a library link
        // (--non-final-ids; the consuming app re-finalizes, and the AAR packager excludes all
        // R classes from classes.jar). Libraries need them too: library code referencing a
        // sibling AAR's R (ui → designsystem.R.drawable) must compile (AGP does the same).
        if (Files.isRegularFile(rTxt)) {
            Map<String, String> finalSymbols = readSymbols(rTxt);
            for (AndroidDeps.Aar aar : aars) {
                if (!Files.isRegularFile(aar.rTxt())) continue;
                String depNamespace = aar.namespace();
                if (depNamespace == null || depNamespace.equals(namespace)) continue;
                writeDepR(gen, depNamespace, readSymbols(aar.rTxt()), finalSymbols);
            }
        }
    }

    /**
     * Link the unit-test resource package into {@code out}: the resources of every AAR on this
     * step's runtime closure plus the module's own, against {@code manifest}. {@code android-res}'s
     * ids stay stable, so the module's compiled {@code R} matches the package, and the module's
     * resources are the archive {@code android-res} compiled. The AAR resources
     * are {@link #DEPS_STEP}'s when this closure merges the same {@code res/} dirs in the same order,
     * and compiled here only when it does not. Runs in a step that has the {@code aapt2} and {@code
     * android-jar} tools.
     */
    static void linkUnitTestApk(TaskExec exec, Path manifest, Path out) throws Exception {
        Path aapt2 = extractAapt2(exec);
        Path res = exec.requireStepOutput("android-res");
        Path ids = res.resolve("packaged").resolve(IDS);
        Path ownFlats = res.resolve("packaged").resolve(OWN_FLATS);
        Path work = exec.privateDir("work");
        List<AndroidDeps.Aar> aars = AndroidDeps.aars(exec.runtimeEntries());
        Path deps = exec.requireStepOutput(DEPS_STEP);
        Path depFlats = sameDirs(resDirs(aars), deps.resolve(DEPS_OUT).resolve(DEPS_DIRS))
                ? depsFlats(deps)
                : compileMergedDeps(exec, aapt2, aars, work, work.resolve(DEPS_FLATS));
        Files.createDirectories(out.getParent());
        TaskExec.ToolRun link = link(
                exec,
                aapt2,
                exec.requireExtra("android-jar"),
                manifest,
                exec.config().string("namespace"),
                exec.config().intValue("compile-sdk", 0),
                exec.config().intValue("min-sdk", 0),
                exec.config().bool("library", false),
                depFlats,
                Files.isRegularFile(ownFlats) ? ownFlats : null,
                out,
                null,
                null,
                null,
                false);
        if (Files.isRegularFile(ids))
            link.arg("--stable-ids").arg(ids.toAbsolutePath().toString());
        TaskExec.ToolRun.Result linked = link.run();
        if (linked.exit() != 0) {
            throw new IllegalStateException("aapt2 link (unit test) failed:\n" + linked.output());
        }
    }

    /** One aapt2 link invocation — binary (R.java + symbols + keep rules) or proto (AAB). */
    private static TaskExec.ToolRun link(
            TaskExec exec,
            Path aapt2,
            Path platformJar,
            Path manifest,
            String namespace,
            long compileSdk,
            long minSdk,
            boolean library,
            @Nullable Path depFlats,
            @Nullable Path ownFlats,
            Path out,
            @Nullable Path gen,
            @Nullable Path rTxt,
            @Nullable Path keepRules,
            boolean proto) {
        TaskExec.ToolRun link = exec.tool(aapt2)
                .arg("link")
                .arg("-o")
                .arg(out.toAbsolutePath().toString())
                .arg("-I")
                .arg(platformJar.toAbsolutePath().toString())
                .arg("--manifest")
                .arg(manifest.toAbsolutePath().toString())
                .arg("--custom-package")
                .arg(namespace)
                .arg("--min-sdk-version")
                .arg(Long.toString(minSdk))
                .arg("--target-sdk-version")
                .arg(Long.toString(compileSdk))
                .arg("--version-code")
                .arg(Long.toString(AndroidDeps.versionCode(exec.config())))
                .arg("--version-name")
                .arg(exec.project().version())
                .arg("--auto-add-overlay");
        if (gen != null) link.arg("--java").arg(gen.toAbsolutePath().toString());
        if (rTxt != null)
            link.arg("--output-text-symbols").arg(rTxt.toAbsolutePath().toString());
        if (keepRules != null)
            link.arg("--proguard").arg(keepRules.toAbsolutePath().toString());
        if (proto) link.arg("--proto-format");
        if (library) link.arg("--non-final-ids");
        if (depFlats != null) link.arg(depFlats.toAbsolutePath().toString());
        if (ownFlats != null) {
            link.arg("-R").arg(ownFlats.toAbsolutePath().toString());
        }
        return link;
    }

    /**
     * Merge every AAR's resources into one tree and compile it once into {@code out} (AGP semantics:
     * two androidx AARs can define the same value resource, and aapt2 rejects value conflicts across
     * separate link inputs; the merger resolves them per resource, earlier classpath entry wins).
     * Returns {@code out}, or null when the merged tree holds no file and nothing was written.
     */
    private static @Nullable Path compileMergedDeps(
            TaskExec exec, Path aapt2, List<AndroidDeps.Aar> aars, Path work, Path out) throws Exception {
        Path merged = ResourceMerger.mergeDepRes(aars, work.resolve("merged-dep-res"));
        if (merged == null || !PathUtil.anyRegularFile(merged, dir -> false, file -> true)) return null;
        exec.label("aapt2 compile (deps)");
        return compileRes(exec, aapt2, merged, out);
    }

    /** The {@code res/} dirs {@link ResourceMerger} merges from {@code aars}, in merge order. */
    private static List<Path> resDirs(List<AndroidDeps.Aar> aars) throws IOException {
        List<Path> out = new ArrayList<>();
        for (AndroidDeps.Aar aar : aars) {
            if (aar.hasRes()) out.add(aar.res().toAbsolutePath());
        }
        return out;
    }

    /** Whether {@code dirs} are exactly the ones {@link #DEPS_STEP} recorded in {@code recorded}. */
    private static boolean sameDirs(List<Path> dirs, Path recorded) throws IOException {
        if (!Files.isRegularFile(recorded)) return false;
        return Files.readAllLines(recorded)
                .equals(dirs.stream().map(Path::toString).toList());
    }

    /** {@link #DEPS_STEP}'s compiled archive, or null when no AAR carried resources. */
    private static @Nullable Path depsFlats(Path depsStep) {
        Path flats = depsStep.resolve(DEPS_OUT).resolve(DEPS_FLATS);
        return Files.isRegularFile(flats) ? flats : null;
    }

    private static Path compileRes(TaskExec exec, Path aapt2, Path resDir, Path out) throws Exception {
        TaskExec.ToolRun.Result compile = exec.tool(aapt2)
                .arg("compile")
                .arg("--dir")
                .arg(resDir.toAbsolutePath().toString())
                .arg("-o")
                .arg(out.toAbsolutePath().toString())
                .run();
        if (compile.exit() != 0) {
            throw new IllegalStateException("aapt2 compile failed for " + resDir + ":\n" + compile.output());
        }
        return out;
    }

    /** {@code "<type> <name>" → "<java-type> <value>"} from an aapt2/AGP {@code R.txt}. */
    private static Map<String, String> readSymbols(Path rTxt) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : Files.readAllLines(rTxt)) {
            // Lines: `int <type> <name> <value>` or `int[] styleable <name> { v1, v2 }`.
            String[] parts = line.strip().split("\\s+", 4);
            if (parts.length < 4) continue;
            out.put(parts[1] + " " + parts[2], parts[0] + " " + parts[3]);
        }
        return out;
    }

    /** One dependency's {@code R} class: its own symbols, the app link's final values. */
    private static void writeDepR(
            Path gen, String namespace, Map<String, String> depSymbols, Map<String, String> finalSymbols)
            throws IOException {
        Map<String, Map<String, String>> byType = new LinkedHashMap<>();
        for (var symbol : depSymbols.entrySet()) {
            String resolved = finalSymbols.getOrDefault(symbol.getKey(), symbol.getValue());
            int space = symbol.getKey().indexOf(' ');
            String type = symbol.getKey().substring(0, space);
            String name = symbol.getKey().substring(space + 1);
            byType.computeIfAbsent(type, k -> new LinkedHashMap<>()).put(name, resolved);
        }
        StringBuilder java = new StringBuilder();
        java.append("/* Generated by jk (non-transitive R) — do not edit. */\n");
        java.append("package ").append(namespace).append(";\n\n");
        java.append("public final class R {\n");
        java.append("    private R() {}\n");
        for (var type : byType.entrySet()) {
            java.append("    public static final class ").append(type.getKey()).append(" {\n");
            java.append("        private ").append(type.getKey()).append("() {}\n");
            for (var field : type.getValue().entrySet()) {
                int space = field.getValue().indexOf(' ');
                String javaType = field.getValue().substring(0, space);
                String value = field.getValue().substring(space + 1);
                java.append("        public static final ")
                        .append(javaType)
                        .append(' ')
                        .append(field.getKey())
                        .append(" = ")
                        .append(value)
                        .append(";\n");
            }
            java.append("    }\n");
        }
        java.append("}\n");
        Path file = gen.resolve(namespace.replace('.', '/')).resolve("R.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, java.toString());
    }

    private static void copyTree(Path from, Path to) throws IOException {
        PathUtil.copyTree(from, to);
    }

    /** Extract the per-OS aapt2 binary from its Maven wrapper jar into the step scratch. */
    private static Path extractAapt2(TaskExec exec) throws IOException {
        return AndroidDeps.extractAapt2(exec.requireExtra("aapt2"), exec.privateDir("tools"));
    }

    /** Every file under {@code dir} (sorted), for tools that want explicit file lists. */
    static List<Path> filesUnder(Path dir, String suffix) throws IOException {
        List<Path> out = new ArrayList<>();
        PathUtil.forEachRegularFile(dir, (f, attrs) -> {
            if (f.toString().endsWith(suffix)) out.add(f);
        });
        out.sort(null);
        return out;
    }
}
