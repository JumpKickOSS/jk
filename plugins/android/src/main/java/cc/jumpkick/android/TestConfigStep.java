// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import cc.jumpkick.plugin.build.TaskExec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes Robolectric's {@code test_config.properties} onto the test runtime classpath (merged
 * manifest, raw resources, aapt2 resource package).
 */
final class TestConfigStep {

    private TestConfigStep() {}

    static void run(TaskExec exec) throws Exception {
        Path manifest = exec.requireStepOutput("android-manifest").resolve("merged/AndroidManifest.xml");
        Path res = exec.requireStepOutput("android-res");
        Path rawRes = res.resolve("raw-res");
        Path apk = res.resolve("packaged/resources.ap_");
        String pkg = exec.config().string("namespace");

        Path out = exec.outputDir("cp").resolve("com/android/tools/test_config.properties");
        Files.createDirectories(out.getParent());
        StringBuilder props = new StringBuilder();
        props.append("android_merged_manifest=")
                .append(manifest.toAbsolutePath())
                .append('\n');
        props.append("android_merged_resources=")
                .append(rawRes.toAbsolutePath())
                .append('\n');
        // Assets: the project dir's assets/ when present (merged-assets folding is packaging-time).
        Path assets = AndroidDeps.androidFile(exec.moduleDir(), "assets");
        if (Files.isDirectory(assets)) {
            props.append("android_merged_assets=")
                    .append(assets.toAbsolutePath())
                    .append('\n');
        }
        props.append("android_resource_apk=").append(apk.toAbsolutePath()).append('\n');
        props.append("android_custom_package=").append(pkg).append('\n');
        Files.writeString(out, props.toString(), StandardCharsets.UTF_8);
        exec.label("test_config.properties for " + pkg);
    }
}
