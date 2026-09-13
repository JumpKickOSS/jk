// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OrphanedToolEnvsTest {

    @Test
    void an_env_is_orphaned_by_the_root_holding_it_or_the_root_holding_its_classpath(@TempDir Path home)
            throws IOException {
        Path state = home.resolve("state");
        Path envs = state.resolve("tools/envs");
        Path store = home.resolve("store");
        Path bin = Files.createDirectories(home.resolve("bin"));
        Path casJar = store.resolve("sha256/ab/widget.jar");
        installed(envs, bin, "widget", casJar, true);
        installed(envs, bin, "local", home.resolve("elsewhere.jar"), false);
        // A reserved stem is jk's own, never a tool.
        Files.createDirectories(envs.resolve("jk"));
        Files.writeString(bin.resolve("jk"), "the client");

        List<OrphanedToolEnvs.Orphan> byStore = OrphanedToolEnvs.under(envs, bin, List.of(store));
        assertThat(byStore).extracting(OrphanedToolEnvs.Orphan::name).containsExactly("widget");
        assertThat(byStore.getFirst().envDir())
                .isEqualTo(envs.resolve("widget").toAbsolutePath().normalize());
        assertThat(byStore.getFirst().launchers())
                .as("both launcher spellings go with the tool")
                .containsExactly(
                        bin.resolve("widget").toAbsolutePath().normalize(),
                        bin.resolve("widget.cmd").toAbsolutePath().normalize());
        assertThat(byStore.getFirst().orphanedBy())
                .isEqualTo(store.toAbsolutePath().normalize());

        assertThat(OrphanedToolEnvs.under(envs, bin, List.of(state)))
                .as("the state root holds every env")
                .extracting(OrphanedToolEnvs.Orphan::name)
                .containsExactly("local", "widget");
        assertThat(OrphanedToolEnvs.under(envs, bin, List.of(home.resolve("cache"))))
                .as("a root reaching neither env nor classpath orphans nothing")
                .isEmpty();
        assertThat(OrphanedToolEnvs.under(envs, bin, List.of())).isEmpty();
    }

    @Test
    void removing_an_orphan_takes_the_env_and_every_launcher_and_leaves_its_neighbours(@TempDir Path home)
            throws IOException {
        Path envs = home.resolve("state/tools/envs");
        Path store = home.resolve("store");
        Path bin = Files.createDirectories(home.resolve("bin"));
        installed(envs, bin, "widget", store.resolve("sha256/ab/widget.jar"), true);
        installed(envs, bin, "local", home.resolve("elsewhere.jar"), false);

        for (OrphanedToolEnvs.Orphan orphan : OrphanedToolEnvs.under(envs, bin, List.of(store))) {
            OrphanedToolEnvs.remove(orphan);
        }

        assertThat(envs.resolve("widget")).doesNotExist();
        assertThat(bin.resolve("widget")).doesNotExist();
        assertThat(bin.resolve("widget.cmd")).doesNotExist();
        assertThat(envs.resolve("local/env.json")).exists();
        assertThat(bin.resolve("local")).exists();
    }

    private static void installed(Path envs, Path bin, String name, Path classpath, boolean windowsToo)
            throws IOException {
        Path env = Files.createDirectories(envs.resolve(name));
        String jar = classpath.toAbsolutePath().toString().replace("\\", "\\\\");
        Files.writeString(env.resolve("env.json"), "{\"binName\": \"" + name + "\", \"classpath\": [\"" + jar + "\"]}");
        Files.writeString(bin.resolve(name), "#!/usr/bin/env bash\nexec java -cp " + jar + " Main \"$@\"\n");
        if (windowsToo) Files.writeString(bin.resolve(name + ".cmd"), "@echo off\r\n");
    }
}
