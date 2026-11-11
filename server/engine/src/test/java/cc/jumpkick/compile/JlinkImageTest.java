// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import cc.jumpkick.host.Os;
import cc.jumpkick.host.Processes;
import cc.jumpkick.model.Jlink;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The pure parts of the {@code [jlink]} image: the module set, the cache choice and the launcher. */
class JlinkImageTest {

    @TempDir
    Path tmp;

    @Test
    void the_module_list_is_jdeps_last_line_then_the_declared_extras() {
        String out = "Warning: split package: a.b\njava.base,java.logging,java.management\n";
        assertThat(JlinkImage.parseModuleDeps(out)).containsExactly("java.base", "java.logging", "java.management");
        assertThat(JlinkImage.mergeModules(JlinkImage.parseModuleDeps(out), List.of("jdk.crypto.ec", "java.logging")))
                .containsExactly("java.base", "java.logging", "java.management", "jdk.crypto.ec", "jdk.jdwp.agent");
        assertThat(JlinkImage.mergeModules(List.of(), List.of())).containsExactly("java.base", "jdk.jdwp.agent");
    }

    @Test
    void the_cache_is_an_aot_cache_from_jdk_25_and_a_cds_archive_below() {
        assumeFalse(Os.isWindows(), "Windows trains no cache");
        assertThat(JlinkImage.cache(Jlink.defaults(), 25)).isEqualTo(JlinkImage.Cache.AOT);
        assertThat(JlinkImage.cache(Jlink.defaults(), 21)).isEqualTo(JlinkImage.Cache.CDS);
        assertThat(JlinkImage.cache(with(Jlink.AotCache.CDS), 27)).isEqualTo(JlinkImage.Cache.CDS);
        assertThat(JlinkImage.cache(with(Jlink.AotCache.OFF), 27)).isEqualTo(JlinkImage.Cache.NONE);
    }

    @Test
    void the_launcher_runs_the_image_java_with_flags_cache_default_args_and_debug() throws Exception {
        assumeFalse(Os.isWindows(), "the POSIX launcher");
        Path image = Files.createDirectories(tmp.resolve("my app-jri"));
        Files.createDirectories(image.resolve("bin"));
        Files.createDirectories(image.resolve("app"));
        Files.writeString(image.resolve("app/app.aot"), "x");
        Path java = image.resolve("bin/java");
        Files.writeString(java, "#!/bin/sh\nfor a in \"$@\"; do printf '%s\\n' \"$a\"; done\n");
        java.toFile().setExecutable(true);
        Jlink config = new Jlink(
                List.of(),
                true,
                Jlink.AotCache.AUTO,
                List.of("-Xmx64m", "-Done=it's"),
                List.of("serve", "a b"),
                60,
                true,
                Map.of());
        Path start = image.resolve("bin/start");
        Files.writeString(start, JlinkImage.posixLauncher(config, "app.jar", JlinkImage.Cache.AOT, "app.aot"));
        start.toFile().setExecutable(true);
        String dir = image.toRealPath().toString();

        assertThat(run(start))
                .containsExactly(
                        "-Xmx64m",
                        "-Done=it's",
                        "-XX:AOTCache=" + dir + "/app/app.aot",
                        "-jar",
                        dir + "/app/app.jar",
                        "serve",
                        "a b");
        assertThat(run(start, "other")).endsWith("-jar", dir + "/app/app.jar", "other");
        List<String> debug = run(start, "--debug");
        assertThat(debug).noneMatch(a -> a.startsWith("-XX:AOTCache"));
        assertThat(debug).anyMatch(a -> a.startsWith("-agentlib:jdwp=") && a.endsWith("address=*:5005"));
        assertThat(debug).endsWith("serve", "a b");
    }

    @Test
    void a_missing_main_class_a_jdeps_failure_and_a_jdk_jlink_cannot_link_from_are_named() throws Exception {
        assumeFalse(Os.isWindows(), "fake JDK tools are shell scripts");
        Path jdk = fakeJdk("echo 'Error: bad.jar is a broken jar'; exit 1", "echo unused; exit 0");
        assertThatThrownBy(() -> JlinkImage.build(plan(jdk, ""), m -> {}))
                .hasMessageContaining("needs a main class")
                .hasMessageContaining("[application] main");
        assertThatThrownBy(() -> JlinkImage.build(plan(jdk, "a.Main"), m -> {}))
                .hasMessageContaining("jdeps could not work out")
                .hasMessageContaining("bad.jar is a broken jar");

        Path noJmods = fakeJdk(
                "echo java.base,java.logging",
                "echo 'Error: --module-path is not specified and this runtime image does not contain jmods"
                        + " directory.'; exit 1");
        assertThatThrownBy(() -> JlinkImage.build(plan(noJmods, "a.Main"), m -> {}))
                .hasMessageContaining("jlink failed")
                .hasMessageContaining("does not contain jmods directory")
                .hasMessageContaining("has no jmods/ directory");
    }

    private Path fakeJdk(String jdeps, String jlink) throws Exception {
        Path home = Files.createTempDirectory(tmp, "jdk");
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("release"), "JAVA_VERSION=\"21.0.1\"\n");
        for (var tool : Map.of("jdeps", jdeps, "jlink", jlink).entrySet()) {
            Path exe = home.resolve("bin").resolve(tool.getKey());
            Files.writeString(exe, "#!/bin/sh\n" + tool.getValue() + "\n");
            exe.toFile().setExecutable(true);
        }
        return home;
    }

    private JlinkImage.Plan plan(Path jdk, String mainClass) throws Exception {
        Path jar = Files.writeString(tmp.resolve("app.jar"), "not read by a fake jdeps");
        return new JlinkImage.Plan(
                jdk,
                21,
                jar,
                "app",
                List.of(),
                mainClass,
                Jlink.defaults(),
                tmp.resolve("target/app-jri"),
                tmp.resolve("target/jlink-train"),
                Map.of());
    }

    private static Jlink with(Jlink.AotCache cache) {
        return new Jlink(List.of(), true, cache, List.of(), List.of(), 60, true, Map.of());
    }

    private static List<String> run(Path script, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(script.toString()));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().remove("JAVA_OPTS");
        Processes.Captured run = Processes.capture(pb, Duration.ofSeconds(30));
        assertThat(run.exit()).as(run.output()).isZero();
        return run.output().lines().toList();
    }
}
