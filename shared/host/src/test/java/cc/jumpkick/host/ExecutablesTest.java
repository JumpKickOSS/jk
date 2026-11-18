// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Windows looks for a bare program name in the current directory before System32 and {@code PATH};
 * the paths handed to it here never leave that lookup to Windows.
 */
class ExecutablesTest {

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void a_windows_system_tool_is_its_system32_path() {
        Path reg = Path.of(Executables.windowsSystem("reg.exe"));
        assertThat(reg).isAbsolute().isRegularFile();
        assertThat(reg.toString()).endsWithIgnoringCase("\\System32\\reg.exe");
        assertThat(Path.of(Executables.windowsPowerShell())).isAbsolute().isRegularFile();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void the_persisted_home_query_starts_reg_by_its_path() {
        List<String> argv = PersistedHome.regQuery();
        assertThat(Path.of(argv.get(0))).isAbsolute().isRegularFile();
        assertThat(argv.subList(1, argv.size())).containsExactly("query", "HKCU\\Environment", "/v", "JK_HOME");
    }

    @Test
    void a_tool_is_found_under_an_absolute_path_entry_and_never_under_a_relative_one(@TempDir Path tmp)
            throws IOException {
        Path planted = Files.createDirectories(tmp.resolve("repo"));
        Files.createFile(planted.resolve("mise.exe"));
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path real = Files.createFile(bin.resolve("mise.exe"));
        Path cwd = Path.of("").toAbsolutePath();
        assumeThat(cwd.getRoot()).isEqualTo(planted.getRoot());
        String relative = cwd.relativize(planted).toString();

        String path = String.join(SearchPath.SEPARATOR, "", ".", relative, bin.toString());

        assertThat(Executables.onWindowsPath("mise", path)).isEqualTo(real.toString());
        assertThat(Executables.onWindowsPath("mise", String.join(SearchPath.SEPARATOR, "", ".", relative)))
                .as("only relative entries, one of which holds the tool")
                .isNull();
    }

    @Test
    void exe_is_added_only_to_a_name_without_an_extension(@TempDir Path tmp) throws IOException {
        Files.createFile(tmp.resolve("jbang.cmd"));
        assertThat(Executables.onWindowsPath("jbang", tmp.toString())).isNull();
        assertThat(Executables.onWindowsPath("jbang.cmd", tmp.toString()))
                .isEqualTo(tmp.resolve("jbang.cmd").toString());
    }

    @Test
    void a_name_with_a_directory_is_taken_as_written() {
        assertThat(Executables.onWindowsPath("C:\\tools\\jabba.exe", null)).isEqualTo("C:\\tools\\jabba.exe");
        assertThat(Executables.onWindowsPath("bin/jabba", null)).isEqualTo("bin/jabba");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void off_windows_a_bare_name_is_passed_as_written() {
        assertThat(Executables.onPath("mise")).isEqualTo("mise");
    }
}
