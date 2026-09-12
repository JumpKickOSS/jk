// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The native-image packaging key follows every input that shapes the binary. The trained
 * reachability metadata reaches native-image as a directory named in the args, so its content
 * has to be in the key on its own: a {@code jk train} with another workload rewrites the files
 * behind the same path, and a key over the args alone would restore the previous workload's
 * binary.
 */
class NativeImageKeyTest {

    @Test
    void the_train_reachability_content_is_an_image_input(@TempDir Path tmp) throws Exception {
        Path javaHome = Files.createDirectories(tmp.resolve("graal"));
        Files.writeString(javaHome.resolve("release"), "JAVA_VERSION=\"25\"\n");
        Path jar = Files.writeString(tmp.resolve("app.jar"), "app");
        Path out = tmp.resolve("target/native/app");
        Path train = Files.createDirectories(tmp.resolve("target/train/merged/reachability"));
        Files.writeString(train.resolve("reachability-metadata.json"), "{\"workload\":\"first\"}");
        List<String> args = List.of("-H:ConfigurationFileDirectories=" + train.toAbsolutePath());

        String untrained = PlannerNative.imageKey(javaHome, List.of(jar), args, "app.Main", false, out, null, null)
                .key();
        String first = PlannerNative.imageKey(javaHome, List.of(jar), args, "app.Main", false, out, null, train)
                .key();
        assertThat(first).as("the train dir is an input when it is used").isNotEqualTo(untrained);
        assertThat(PlannerNative.imageKey(javaHome, List.of(jar), args, "app.Main", false, out, null, train)
                        .key())
                .as("the same trained content keys the same image")
                .isEqualTo(first);

        Files.writeString(train.resolve("reachability-metadata.json"), "{\"workload\":\"second\"}");
        assertThat(PlannerNative.imageKey(javaHome, List.of(jar), args, "app.Main", false, out, null, train)
                        .key())
                .as("a retrain behind the same path is a different image")
                .isNotEqualTo(first);
    }

    @Test
    void the_train_dir_counts_only_when_its_merged_metadata_exists(@TempDir Path tmp) throws Exception {
        Path module = Files.createDirectories(tmp.resolve("m"));
        Files.writeString(module.resolve("jk.toml"), """
                group = "t"
                name = "m"
                version = "0.1.0"
                java = 25
                """);
        var layout = BuildLayout.of(module, JkBuildParser.parse(module.resolve("jk.toml")));
        assertThat(PlannerNative.trainReachabilityDir(layout)).isNull();

        Path train = Files.createDirectories(layout.moduleTargetDir().resolve("train/merged/reachability"));
        assertThat(PlannerNative.trainReachabilityDir(layout))
                .as("an empty dir is not a trained workload")
                .isNull();
        Files.writeString(train.resolve("reachability-metadata.json"), "{}");
        assertThat(PlannerNative.trainReachabilityDir(layout)).isEqualTo(train);
    }
}
