// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.args.ArgParser;
import cc.jumpkick.command.system.DoctorCommand.Check;
import cc.jumpkick.command.system.DoctorCommand.Status;
import cc.jumpkick.host.SearchPath;
import cc.jumpkick.model.command.Exit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** The doctor's {@code path} row: whether the {@code jk} on {@code PATH} is the installed one; and its flags. */
class DoctorPathTest {

    private static Path launcher(Path dir, String name) throws Exception {
        Path file = Files.writeString(Files.createDirectories(dir).resolve(name), "#!/bin/sh\n");
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        return file;
    }

    private static String path(Path... dirs) {
        StringBuilder out = new StringBuilder();
        for (Path dir : dirs) {
            if (!out.isEmpty()) out.append(SearchPath.SEPARATOR);
            out.append(dir);
        }
        return out.toString();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void another_jk_first_on_path_shadows_the_installed_one(@TempDir Path tmp) throws Exception {
        Path bin = tmp.resolve("home/bin");
        Path installed = launcher(bin, "jk");
        Path other = launcher(tmp.resolve("usr/local/bin"), "jk");

        Check check = DoctorPath.check(path(tmp.resolve("usr/local/bin"), bin), bin, false);

        assertThat(check.status()).isEqualTo(Status.WARN);
        assertThat(check.detail()).isEqualTo(other + " shadows " + installed + "; put " + bin + " first on PATH");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void the_installed_jk_first_on_path_is_ok(@TempDir Path tmp) throws Exception {
        Path bin = tmp.resolve("home/bin");
        Path installed = launcher(bin, "jk");
        launcher(tmp.resolve("usr/local/bin"), "jk");
        Path empty = Files.createDirectories(tmp.resolve("empty"));

        Check check = DoctorPath.check(path(empty, bin, tmp.resolve("usr/local/bin")), bin, false);

        assertThat(check).isEqualTo(new Check(Status.OK, "path", "jk runs from " + installed));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void a_file_named_jk_that_cannot_run_shadows_nothing(@TempDir Path tmp) throws Exception {
        Path bin = tmp.resolve("home/bin");
        launcher(bin, "jk");
        Files.writeString(Files.createDirectories(tmp.resolve("notes")).resolve("jk"), "not a program\n");

        Check check = DoctorPath.check(path(tmp.resolve("notes"), bin), bin, false);

        assertThat(check.status()).isEqualTo(Status.OK);
    }

    @Test
    void the_install_dir_missing_from_path_is_a_warning(@TempDir Path tmp) throws Exception {
        Path bin = tmp.resolve("home/bin");
        launcher(bin, "jk.exe");

        Check check = DoctorPath.check(path(tmp.resolve("elsewhere")), bin, true);

        assertThat(check).isEqualTo(new Check(Status.WARN, "path", bin + " is not on PATH; run `jk activate`"));
    }

    @Test
    void on_windows_a_jk_cmd_found_first_shadows_the_installed_exe(@TempDir Path tmp) throws Exception {
        Path bin = tmp.resolve("home/bin");
        launcher(bin, "jk.exe");
        Path other = launcher(tmp.resolve("scoop/shims"), "jk.cmd");

        Check check = DoctorPath.check(path(tmp.resolve("scoop/shims"), bin), bin, true);

        assertThat(check.status()).isEqualTo(Status.WARN);
        assertThat(check.detail()).startsWith(other + " shadows ");
    }

    @Test
    void nothing_installed_is_nothing_to_check(@TempDir Path tmp) {
        Check check = DoctorPath.check(path(tmp), tmp.resolve("home/bin"), false);

        assertThat(check.status()).isEqualTo(Status.OK);
    }

    @Test
    void engine_and_no_engine_together_are_a_usage_error() throws Exception {
        DoctorCommand doctor = new DoctorCommand();

        assertThat(doctor.run(ArgParser.parse(doctor, List.of("--engine", "--no-engine"))))
                .isEqualTo(Exit.USAGE);
        assertThat(doctor.run(ArgParser.parse(doctor, List.of("-en")))).isEqualTo(Exit.USAGE);
    }
}
