// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.engine.EngineFleet;
import cc.jumpkick.cli.engine.EngineProbe;
import cc.jumpkick.command.project.OutdatedCommand;
import cc.jumpkick.command.system.DoctorCommand;
import cc.jumpkick.command.system.EngineAotCommand;
import cc.jumpkick.command.system.EngineStatusCommand;
import cc.jumpkick.command.system.EnvCommand;
import cc.jumpkick.config.SecretRedactor;
import cc.jumpkick.util.AotManifest;
import cc.jumpkick.wire.protocol.OutdatedReport;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The bytes the commands' {@code --output json} shapes had when they were string concatenation,
 * spelled out. The builders now write through {@code JsonFields}; a reordered field, a changed null
 * spelling or a dropped optional is a different line to every script that parses these.
 */
class CommandJsonFrozenBytesTest {
    @Test
    void engine_status_running_and_not_running() {
        EngineProbe.Status s = new EngineProbe.Status(
                "0.13.0",
                4242,
                1_700_000_000_000L,
                2,
                1,
                false,
                100,
                200,
                300,
                400,
                0,
                "http://127.0.0.1:8910/",
                null,
                "http://127.0.0.1:8910/mcp",
                null,
                8,
                32_000L,
                16_000L,
                0.25,
                1.5,
                "epoch-1");
        assertThat(EngineStatusCommand.runningJson(s, 60, List.of()))
                .isEqualTo("{\"running\":true,\"pid\":4242,\"version\":\"0.13.0\",\"startedAt\":1700000000000,"
                        + "\"uptimeSeconds\":60,\"activeRequests\":2,\"heapUsedBytes\":100,\"heapCommittedBytes\":200,"
                        + "\"heapMaxBytes\":300,\"rssBytes\":400,\"aotTrainingPid\":0,\"cores\":8,\"totalMemoryBytes\":32000,"
                        + "\"availableMemoryBytes\":16000,\"systemCpuLoad\":0.25,\"systemLoadAverage\":1.5,"
                        + "\"engineEpoch\":\"epoch-1\",\"httpUrl\":\"http://127.0.0.1:8910/\",\"httpError\":null,"
                        + "\"mcpUrl\":\"http://127.0.0.1:8910/mcp\",\"engines\":[]}");
        EngineProbe.Status withVfs = new EngineProbe.Status(
                "0.13.0",
                1,
                2,
                0,
                0,
                false,
                0,
                0,
                0,
                0,
                0,
                null,
                "refused",
                null,
                "{\"files\":3}",
                1,
                0,
                0,
                0.0,
                0.0,
                null);
        assertThat(EngineStatusCommand.runningJson(withVfs, 0, List.of()))
                .contains(
                        "\"httpUrl\":null,\"httpError\":\"refused\",\"mcpUrl\":null,\"vfs\":{\"files\":3},\"engines\":[]}");
        assertThat(EngineStatusCommand.notRunningJson(0, List.of())).isEqualTo("{\"running\":false,\"engines\":[]}");
        assertThat(EngineStatusCommand.notRunningJson(777, List.of()))
                .isEqualTo("{\"running\":false,\"unresponsivePid\":777,\"engines\":[]}");
        EngineFleet.Member silent = new EngineFleet.Member(null, null, null, 99, false);
        EngineFleet.Member live = new EngineFleet.Member(null, null, withVfs, 1, true);
        assertThat(EngineStatusCommand.enginesJson(List.of(silent, live)))
                .isEqualTo("[{\"id\":\"untracked-99\",\"pid\":99,\"current\":false,\"responsive\":false},"
                        + "{\"id\":\"untracked-1\",\"pid\":1,\"current\":true,\"responsive\":true,\"startedAt\":2,"
                        + "\"activeBuildPlans\":0,\"draining\":false,\"version\":\"0.13.0\"}]");
    }

    @Test
    void outdated_rows() {
        var row = new OutdatedReport.Row("app", "g:a", "g:a", "compile", "1.0", null, "2.0", "major");
        assertThat(OutdatedCommand.toJson(List.of(row)))
                .isEqualTo("[{\"module\":\"app\",\"dependency\":\"g:a\",\"display\":\"g:a\",\"scope\":\"compile\","
                        + "\"current\":\"1.0\",\"compatible\":null,\"latest\":\"2.0\",\"tip\":\"major\"}]");
        assertThat(OutdatedCommand.toJson(List.of())).isEqualTo("[]");
    }

    @Test
    void env_rows_are_one_per_line_inside_a_bracket_pair() {
        var plain = new EnvCommand.Row("HOME", "/h", "shell", false, null);
        var secret = new EnvCommand.Row("TOKEN", "abcdefghijklmnopqrstuvwxyz", ".env", true, "short");
        assertThat(EnvCommand.toJson(List.of(plain, secret), false))
                .isEqualTo("[\n  {\"name\":\"HOME\",\"value\":\"/h\",\"source\":\"shell\",\"secret\":false},\n"
                        + "  {\"name\":\"TOKEN\",\"value\":\"" + SecretRedactor.MASK
                        + "\",\"source\":\".env\",\"secret\":true}\n]\n");
        assertThat(EnvCommand.toJson(List.of(secret), true))
                .isEqualTo("[\n  {\"name\":\"TOKEN\",\"value\":\"" + SecretRedactor.MASK
                        + "\",\"source\":\".env\",\"secret\":true,\"shadowed\":\"short\"}\n]\n");
        assertThat(EnvCommand.toJson(List.of(), false)).isEqualTo("[\n]\n");
    }

    @Test
    void engine_aot_caches_carry_only_the_fields_they_know(@TempDir Path dir) {
        var bare = new AotManifest.Entry(
                "a.aot", null, "", null, null, null, null, null, null, List.of(), List.of(), null, null, null, null,
                null, null);
        var full = new AotManifest.Entry(
                "b.aot",
                "java-compiler",
                "k1",
                "ok",
                12L,
                "/jdk",
                "temurin",
                "25",
                "serial",
                List.of("x.jar", "y.jar"),
                List.of("-Xmx1g"),
                "0.13.0",
                "engine.jar",
                34L,
                56L,
                "2026-09-07",
                "2026-09-08");
        assertThat(EngineAotCommand.toJson(dir, List.of(bare, full)))
                .isEqualTo("{\"directory\":\"" + dir + "\",\"manifest\":false,\"caches\":["
                        + "{\"file\":\"a.aot\"},"
                        + "{\"file\":\"b.aot\",\"tool\":\"java-compiler\",\"key\":\"k1\",\"status\":\"ok\",\"sizeBytes\":12,"
                        + "\"jdkHome\":\"/jdk\",\"jdkVendor\":\"temurin\",\"jdkVersion\":\"25\",\"gc\":\"serial\","
                        + "\"jkVersion\":\"0.13.0\",\"engineJar\":\"engine.jar\",\"engineJarSize\":34,\"engineJarMtimeMs\":56,"
                        + "\"created\":\"2026-09-07\",\"lastUsed\":\"2026-09-08\",\"classpath\":[\"x.jar\",\"y.jar\"],"
                        + "\"jvmFlags\":[\"-Xmx1g\"]}]}");
    }

    @Test
    void doctor_report() {
        var ok = new DoctorCommand.Check(DoctorCommand.Status.OK, "Engine", "running");
        var warn = new DoctorCommand.Check(DoctorCommand.Status.WARN, "Cache", "large");
        var fail = new DoctorCommand.Check(DoctorCommand.Status.FAIL, "JDK", "missing \"25\"");
        assertThat(DoctorCommand.checkJson(fail)).isEqualTo("{\"status\":\"fail\",\"detail\":\"missing \\\"25\\\"\"}");
        assertThat(DoctorCommand.reportJson(ok, warn, fail, ok, ok, 3, 1, 0, 0, 2, 0, null))
                .isEqualTo(
                        "{\"engine\":{\"status\":\"ok\",\"detail\":\"running\"},"
                                + "\"cache\":{\"status\":\"warn\",\"detail\":\"large\"},"
                                + "\"jdk\":{\"status\":\"fail\",\"detail\":\"missing \\\"25\\\"\"},"
                                + "\"lock\":{\"status\":\"ok\",\"detail\":\"running\"},"
                                + "\"shell\":{\"status\":\"ok\",\"detail\":\"running\"},"
                                + "\"tools\":{\"healthy\":3,\"pruned\":1,\"verified\":0,\"drifted\":0,\"firstSeen\":2,\"empty\":0,\"error\":null}}");
        assertThat(DoctorCommand.reportJson(ok, ok, ok, ok, ok, 0, 0, 0, 0, 0, 0, "scan failed"))
                .endsWith("\"empty\":0,\"error\":\"scan failed\"}}");
    }
}
