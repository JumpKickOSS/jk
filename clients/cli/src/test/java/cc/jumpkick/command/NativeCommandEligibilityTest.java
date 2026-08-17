// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Workspace {@code jk native} must prefer modules with a {@code [native]} table so monorepos full
 * of plugin harness mains do not each start a multi-minute native-image (progress [1/8] restarting
 * forever).
 */
class NativeCommandEligibilityTest {

    @TempDir
    Path tmp;

    @Test
    void prefers_modules_with_native_table_over_bare_mains() throws Exception {
        Path plugin = module("plugin", true, false);
        Path cli = module("cli", true, true);
        Path graal = Files.createDirectories(tmp.resolve("graal"));

        Map<Path, Path> homes = NativeCommand.graalHomesForModules(List.of(plugin, cli), graal, null);
        assertThat(homes).containsOnlyKeys(cli);
        assertThat(homes.get(cli)).isEqualTo(graal);
    }

    @Test
    void falls_back_to_unique_main_when_no_native_table() throws Exception {
        Path app = module("app", true, false);
        Path lib = module("lib", false, false);
        Path graal = Files.createDirectories(tmp.resolve("graal"));

        Map<Path, Path> homes = NativeCommand.graalHomesForModules(List.of(app, lib), graal, null);
        assertThat(homes).containsOnlyKeys(app);
    }

    @Test
    void enabled_false_module_never_enters_through_the_fallback() throws Exception {
        // JK-2089: [native] enabled = false keeps the table but opts out. With no enabled table
        // anywhere, the fallback must skip the disabled module even though it has a unique main.
        Path off = moduleDisabledNative("off");
        Path app = module("app", true, false);
        Path graal = Files.createDirectories(tmp.resolve("graal"));

        Map<Path, Path> homes = NativeCommand.graalHomesForModules(List.of(off, app), graal, null);
        assertThat(homes).containsOnlyKeys(app);

        // A lone disabled module yields nothing (jk native then fails preflight, by design).
        assertThat(NativeCommand.graalHomesForModules(List.of(off), graal, null))
                .isEmpty();
    }

    private Path moduleDisabledNative(String name) throws Exception {
        Path dir = module(name, true, false);
        Files.writeString(
                dir.resolve("jk.toml"), Files.readString(dir.resolve("jk.toml")) + "\n[native]\nenabled = false\n");
        return dir;
    }

    @Test
    void empty_when_no_mains() throws Exception {
        Path lib = module("lib", false, false);
        Path graal = Files.createDirectories(tmp.resolve("graal"));
        assertThat(NativeCommand.graalHomesForModules(List.of(lib), graal, null))
                .isEmpty();
    }

    private Path module(String name, boolean withMain, boolean withNativeTable) throws Exception {
        Path dir = tmp.resolve(name);
        Files.createDirectories(dir.resolve("src/main/java/ex"));
        if (withMain) {
            Files.writeString(
                    dir.resolve("src/main/java/ex/Main.java"),
                    "package ex; public class Main { public static void main(String[] a) {} }\n");
        } else {
            Files.writeString(dir.resolve("src/main/java/ex/Lib.java"), "package ex; public class Lib {}\n");
        }
        StringBuilder toml = new StringBuilder();
        toml.append("group = \"ex\"\nname = \"").append(name).append("\"\nversion = \"1.0\"\njava = 25\n");
        if (withMain) {
            toml.append("\n[application]\nmain = \"ex.Main\"\n");
        }
        if (withNativeTable) {
            toml.append("\n[native]\nenabled = \"always\"\n");
        }
        Files.writeString(dir.resolve("jk.toml"), toml.toString());
        return dir;
    }
}
