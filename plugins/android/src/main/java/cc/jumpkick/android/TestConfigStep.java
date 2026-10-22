// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import cc.jumpkick.host.DeterministicProperties;
import cc.jumpkick.plugin.build.TaskExec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes Robolectric's {@code test_config.properties} onto the test runtime classpath (unit-test
 * manifest, raw resources, unit-test resource package). The unit-test manifest is AGP's: the
 * module's manifest merged as an application with every AAR on the test runtime closure, so a
 * test-scope AAR's components ({@code ui-test-manifest}'s {@code ComponentActivity}) resolve under
 * Robolectric. Binary-resources mode reads the manifest compiled into the resource package, so the
 * step relinks {@code android-res}'s compiled resources against that manifest.
 */
final class TestConfigStep {

    /** The output holding {@link #TEST_JVM_ARGS}. */
    static final String TEST_JVM = "test-jvm";

    /** One test-JVM flag per line: what Robolectric needs from the JDK, empty without Robolectric. */
    static final String TEST_JVM_ARGS = TEST_JVM + "/jvm.args";

    /**
     * Robolectric intercepts {@code FileDescriptor} through {@code jdk.internal.access.SharedSecrets}
     * and reflects into {@code java.io}; on JDK 17+ neither is reachable without these.
     */
    static final List<String> ROBOLECTRIC_JVM_ARGS = List.of(
            "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED");

    private TestConfigStep() {}

    static void run(TaskExec exec) throws Exception {
        Path manifest = exec.outputDir("manifest").resolve("AndroidManifest.xml");
        ManifestStep.merge(exec, ManifestStep.aarManifests(exec.runtimeEntries()), false, manifest);
        Path res = exec.requireStepOutput("android-res");
        Path rawRes = res.resolve("raw-res");
        Path apk = exec.outputDir("packaged").resolve("resources.ap_");
        exec.label("aapt2 link (unit test)");
        ResourceStep.linkUnitTestApk(exec, manifest, apk);
        String pkg = exec.config().string("namespace");

        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("android_merged_manifest", manifest.toAbsolutePath().toString());
        entries.put("android_merged_resources", rawRes.toAbsolutePath().toString());
        // Assets: the project dir's assets/ when present (merged-assets folding is packaging-time).
        Path assets = AndroidDeps.androidFile(exec.moduleDir(), "assets");
        if (Files.isDirectory(assets)) {
            entries.put("android_merged_assets", assets.toAbsolutePath().toString());
        }
        entries.put("android_resource_apk", apk.toAbsolutePath().toString());
        entries.put("android_custom_package", pkg);

        Path out = exec.outputDir("cp").resolve("com/android/tools/test_config.properties");
        Files.createDirectories(out.getParent());
        // Escape so Windows paths round-trip through Properties.load (Robolectric's reader).
        Files.writeString(out, DeterministicProperties.render(entries), StandardCharsets.UTF_8);
        exec.label("test_config.properties for " + pkg);

        boolean robolectric =
                exec.runtimeEntries().stream().anyMatch(e -> e.group().equals("org.robolectric"));
        Path args = exec.outputDir(TEST_JVM).resolve("jvm.args");
        Files.write(args, robolectric ? ROBOLECTRIC_JVM_ARGS : List.of(), StandardCharsets.UTF_8);
    }
}
