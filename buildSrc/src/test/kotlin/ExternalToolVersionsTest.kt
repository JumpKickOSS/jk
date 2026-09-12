// SPDX-License-Identifier: Apache-2.0

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

@DisabledOnOs(OS.WINDOWS)
class ExternalToolVersionsTest {

    /** An executable shell script named [name] under [bin] with [body] as its script. */
    private fun tool(bin: Path, name: String, body: String): File {
        val f = bin.resolve(name).toFile()
        f.writeText("#!/usr/bin/env sh\n$body\n")
        f.setExecutable(true)
        return f
    }

    @Test
    fun a_probe_that_outlives_the_timeout_is_killed_and_reported_as_timed_out(@TempDir dir: Path) {
        val bin = Files.createDirectories(dir.resolve("bin"))
        // Holds stdout open well past the timeout: what a client that waits on an engine looks like.
        tool(bin, "hangs", "exec sleep 30")
        val started = System.nanoTime()
        val id =
            ExternalToolVersions.identity(
                dir.resolve("cache").toFile(),
                "hangs",
                bin.toString(),
                timeout = Duration.ofMillis(500),
            )
        val elapsed = Duration.ofNanos(System.nanoTime() - started)
        assertThat(id).endsWith("/hangs: version probe timed out")
        assertThat(elapsed).isLessThan(Duration.ofSeconds(10))
    }

    @Test
    fun a_probe_that_answers_yields_its_first_line_behind_the_resolved_path(@TempDir dir: Path) {
        val bin = Files.createDirectories(dir.resolve("bin"))
        val exe = tool(bin, "answers", "printf '\\nfake 1.2.3\\nmore\\n'")
        val id = ExternalToolVersions.identity(dir.resolve("cache").toFile(), "answers", bin.toString())
        assertThat(id).isEqualTo("${exe.absolutePath}: fake 1.2.3")
    }

    @Test
    fun a_probe_that_exits_non_zero_reports_the_exit_code(@TempDir dir: Path) {
        val bin = Files.createDirectories(dir.resolve("bin"))
        val exe = tool(bin, "fails", "echo nope; exit 3")
        val id = ExternalToolVersions.identity(dir.resolve("cache").toFile(), "fails", bin.toString())
        assertThat(id).isEqualTo("${exe.absolutePath}: version probe exited 3")
    }

    @Test
    fun a_tool_missing_from_the_search_path_is_an_absent_identity(@TempDir dir: Path) {
        val bin = Files.createDirectories(dir.resolve("bin"))
        assertThat(ExternalToolVersions.identity(dir.resolve("cache").toFile(), "nowhere", bin.toString()))
            .isEqualTo("nowhere: absent")
    }
}
