// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** JK-1412: start-script clients must materialize with their dist jars, not as a lone script. */
class SelfMaterializeDevDistTest {

    @TempDir
    Path tmp;

    @Test
    void distLibFor_detects_a_start_script_inside_a_dist_tree() throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("install/jk/bin"));
        Path lib = Files.createDirectories(tmp.resolve("install/jk/lib"));
        Path script = Files.writeString(bin.resolve("jk"), "#!/bin/sh\necho hi\n");
        assertThat(SelfCommand.MaterializeSub.distLibFor(script)).isEqualTo(lib);
    }

    @Test
    void distLibFor_rejects_native_binaries_and_stray_scripts() throws Exception {
        // Native image: not a script (no shebang).
        Path bin = Files.createDirectories(tmp.resolve("native/bin"));
        Files.createDirectories(tmp.resolve("native/lib"));
        Path exe = Files.write(bin.resolve("jk"), new byte[] {0x7f, 'E', 'L', 'F'});
        assertThat(SelfCommand.MaterializeSub.distLibFor(exe)).isNull();

        // Script without a bin/ parent or lib/ sibling.
        Path stray = Files.writeString(tmp.resolve("jk"), "#!/bin/sh\n");
        assertThat(SelfCommand.MaterializeSub.distLibFor(stray)).isNull();

        Path binOnly = Files.createDirectories(tmp.resolve("nolib/bin"));
        Path scriptNoLib = Files.writeString(binOnly.resolve("jk"), "#!/bin/sh\n");
        assertThat(SelfCommand.MaterializeSub.distLibFor(scriptNoLib)).isNull();

        assertThat(SelfCommand.MaterializeSub.distLibFor(null)).isNull();
        assertThat(SelfCommand.MaterializeSub.distLibFor(tmp.resolve("missing")))
                .isNull();
    }

    @Test
    void syncDistLibs_copies_jars_and_overwrites_stale_ones() throws Exception {
        Path distLib = Files.createDirectories(tmp.resolve("dist/lib"));
        Files.writeString(distLib.resolve("cli.jar"), "new-cli");
        Files.writeString(distLib.resolve("core.jar"), "core");
        Files.writeString(distLib.resolve("README.txt"), "not a jar");
        Path storeLib = Files.createDirectories(tmp.resolve("store/lib"));
        Files.writeString(storeLib.resolve("cli.jar"), "old-cli");
        Files.writeString(storeLib.resolve("jk-engine.jar"), "engine");

        SelfCommand.MaterializeSub.syncDistLibs(distLib, storeLib);

        assertThat(storeLib.resolve("cli.jar")).hasContent("new-cli");
        assertThat(storeLib.resolve("core.jar")).hasContent("core");
        assertThat(storeLib.resolve("jk-engine.jar")).hasContent("engine"); // untouched
        assertThat(storeLib.resolve("README.txt")).doesNotExist(); // jars only
    }
}
