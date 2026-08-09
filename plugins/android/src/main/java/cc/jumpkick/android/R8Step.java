// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import cc.jumpkick.plugin.build.TaskExec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code android-r8} step (release / {@code minify = true}): R8 full mode over module + runtime
 * classpath against the platform jar → {@code dex/} and retrace artifacts in {@code mapping/}.
 */
final class R8Step {

    private R8Step() {}

    /** The baseline rules every release build gets — kept tiny on purpose. */
    private static final String BASELINE_RULES = """
            # jk android baseline (R8 full mode carries the real defaults)
            -keepattributes Signature,InnerClasses,EnclosingMethod
            -keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
            -keepattributes RuntimeVisibleTypeAnnotations,AnnotationDefault
            -keepattributes SourceFile,LineNumberTable
            -renamesourcefileattribute SourceFile
            """;

    static void run(TaskExec exec) throws Exception {
        Path r8 = exec.requireExtra("r8");
        Path platformJar = JarInputs.jarNamed(exec, exec.requireExtra("android-jar"), "android-platform.jar");
        Path dexOut = exec.outputDir("dex");
        Path mappingOut = exec.outputDir("mapping");
        long minSdk = exec.config().intValue("min-sdk", 0);

        List<Path> classFiles = ResourceStep.filesUnder(exec.classesDir(), ".class");
        if (classFiles.isEmpty()) {
            throw new IllegalStateException("no compiled classes to shrink under " + exec.classesDir());
        }
        List<Path> runtimeJars = new ArrayList<>();
        for (var entry : exec.runtimeEntries()) {
            if (entry.jar() != null) runtimeJars.add(entry.jar());
        }
        // Program inputs are judged by extension too — alias extensionless CAS blobs (JK-1449).
        runtimeJars = JarInputs.jarSuffixed(exec, runtimeJars);

        // Keep-rule collection, baseline → aapt2 → consumer rules → the app's own files.
        List<Path> rules = new ArrayList<>();
        Path baseline = exec.scratch().resolve("baseline-rules.pro");
        Files.writeString(baseline, BASELINE_RULES);
        rules.add(baseline);
        Path aaptRules = exec.requireStepOutput("android-res").resolve("packaged/keep-rules.pro");
        if (Files.isRegularFile(aaptRules)) rules.add(aaptRules);
        for (var entry : exec.runtimeEntries()) {
            if (entry.container() == null) continue;
            Path consumer = entry.container().resolve("proguard.txt");
            if (Files.isRegularFile(consumer)) rules.add(consumer);
        }
        for (String rel : exec.config().stringList("proguard-files")) {
            Path file = exec.moduleDir().resolve(rel);
            if (!Files.isRegularFile(file)) {
                throw new IllegalStateException("proguard-files names a missing file: " + rel);
            }
            rules.add(file);
        }
        // Retrace outputs ride a generated fragment (they are -print* directives, not flags).
        Path outputs = exec.scratch().resolve("output-rules.pro");
        Files.writeString(
                outputs,
                "-printseeds " + mappingOut.resolve("seeds.txt").toAbsolutePath() + "\n" + "-printusage "
                        + mappingOut.resolve("usage.txt").toAbsolutePath() + "\n");
        rules.add(outputs);

        exec.label("R8 (" + classFiles.size() + " classes + " + runtimeJars.size() + " jars)");
        TaskExec.ToolRun run = exec.java()
                .classpath(List.of(r8))
                .mainClass("com.android.tools.r8.R8")
                .arg("--release")
                .arg("--lib")
                .arg(platformJar.toAbsolutePath().toString())
                .arg("--min-api")
                .arg(Long.toString(minSdk))
                .arg("--output")
                .arg(dexOut.toAbsolutePath().toString())
                .arg("--pg-map-output")
                .arg(mappingOut.resolve("mapping.txt").toAbsolutePath().toString());
        for (Path conf : rules) {
            run.arg("--pg-conf").arg(conf.toAbsolutePath().toString());
        }
        for (Path f : classFiles) run.arg(f.toAbsolutePath().toString());
        for (Path jar : runtimeJars) run.arg(jar.toAbsolutePath().toString());
        TaskExec.ToolRun.Result result = run.run();
        if (result.exit() != 0) {
            throw new IllegalStateException("R8 failed:\n" + result.output());
        }
    }
}
