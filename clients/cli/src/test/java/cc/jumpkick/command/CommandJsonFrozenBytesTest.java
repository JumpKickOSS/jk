// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.engine.EngineFleet;
import cc.jumpkick.cli.engine.EngineProbe;
import cc.jumpkick.command.project.OutdatedCommand;
import cc.jumpkick.command.system.DoctorCommand;
import cc.jumpkick.command.system.EngineStatusCommand;
import cc.jumpkick.command.system.EnvCommand;
import cc.jumpkick.command.system.MavenSettingsRows;
import cc.jumpkick.command.system.RepoStores;
import cc.jumpkick.config.SecretRedactor;
import cc.jumpkick.wire.protocol.OutdatedReport;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The bytes the commands' {@code --output json} shapes had when they were string concatenation,
 * spelled out. The builders now write through {@code JsonFields}; a reordered field, a changed null
 * spelling or a dropped optional is a different line to every script that parses these.
 */
class CommandJsonFrozenBytesTest {
    @Test
    // The nulls are deliberate: the status fields the engine leaves absent.
    @SuppressWarnings("NullAway")
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
                "http://127.0.0.1:8910/",
                null,
                "http://127.0.0.1:8910/mcp",
                null,
                8,
                32_000L,
                16_000L,
                0.25,
                1.5,
                "epoch-1",
                3,
                40_960,
                1_700_000_000_500L,
                "",
                2,
                "/home/me/src/jk",
                List.of());
        assertThat(EngineStatusCommand.runningJson(s, 60, List.of()))
                .isEqualTo("{\"running\":true,\"pid\":4242,\"version\":\"0.13.0\",\"startedAt\":1700000000000,"
                        + "\"uptimeSeconds\":60,\"activeRequests\":2,\"idleDropped\":3,\"queuedBuildPlans\":2,\"jobs\":[],\"heapUsedBytes\":100,\"heapCommittedBytes\":200,"
                        + "\"heapMaxBytes\":300,\"rssBytes\":400,\"cores\":8,\"totalMemoryBytes\":32000,"
                        + "\"availableMemoryBytes\":16000,\"systemCpuLoad\":0.25,\"systemLoadAverage\":1.5,"
                        + "\"engineEpoch\":\"epoch-1\",\"logBytes\":40960,\"logRolledAt\":1700000000500,"
                        + "\"ignoredSignals\":\"\",\"installSource\":\"/home/me/src/jk\","
                        + "\"httpUrl\":\"http://127.0.0.1:8910/\",\"httpError\":null,"
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
                null,
                "refused",
                null,
                "{\"files\":3}",
                1,
                0,
                0,
                0.0,
                0.0,
                null,
                -1,
                -1,
                -1,
                null,
                0,
                null,
                List.of());
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
    void doctor_report() {
        var ok = new DoctorCommand.Check(DoctorCommand.Status.OK, "Engine", "running");
        var warn = new DoctorCommand.Check(DoctorCommand.Status.WARN, "Cache", "large");
        var fail = new DoctorCommand.Check(DoctorCommand.Status.FAIL, "JDK", "missing \"25\"");
        assertThat(DoctorCommand.checkJson(fail)).isEqualTo("{\"status\":\"fail\",\"detail\":\"missing \\\"25\\\"\"}");
        var noWorkers = new DoctorCommand.Workers(List.of(), null);
        var noRepos = new RepoStores.Stores(List.of(), null);
        var noSettings = new MavenSettingsRows.Rows(List.of(), List.of(), List.of(), List.of(), null);
        String emptySettings = "\"settings\":{\"files\":[],\"mirrors\":[],\"proxies\":[],\"repositories\":[]}";
        assertThat(DoctorCommand.reportJson(
                        ok, warn, ok, fail, ok, ok, warn, 3, 1, 0, 0, 2, 0, null, noWorkers, noRepos, noSettings))
                .isEqualTo("{\"engine\":{\"status\":\"ok\",\"detail\":\"running\"},"
                        + "\"cache\":{\"status\":\"warn\",\"detail\":\"large\"},"
                        + "\"state\":{\"status\":\"ok\",\"detail\":\"running\"},"
                        + "\"jdk\":{\"status\":\"fail\",\"detail\":\"missing \\\"25\\\"\"},"
                        + "\"lock\":{\"status\":\"ok\",\"detail\":\"running\"},"
                        + "\"shell\":{\"status\":\"ok\",\"detail\":\"running\"},"
                        + "\"mvn\":{\"status\":\"warn\",\"detail\":\"large\"},"
                        + "\"tools\":{\"healthy\":3,\"pruned\":1,\"verified\":0,\"drifted\":0,\"firstSeen\":2,\"empty\":0,\"error\":null},"
                        + "\"workers\":[],\"repos\":[]," + emptySettings + "}");
        assertThat(DoctorCommand.reportJson(
                        ok, ok, ok, ok, ok, ok, ok, 0, 0, 0, 0, 0, 0, "scan failed", noWorkers, noRepos, noSettings))
                .endsWith("\"empty\":0,\"error\":\"scan failed\"},\"workers\":[],\"repos\":[]," + emptySettings + "}");
        var one = new DoctorCommand.Workers(
                List.of(new DoctorCommand.Worker(
                        "jk-image-builder",
                        "0.13.3",
                        "jk-local",
                        "/s/w.jar",
                        "/s/w.pom",
                        2,
                        List.of("/s/w.jar"),
                        null,
                        null,
                        "ab12cd34ef56" + "0".repeat(52))),
                null);
        var repos = new RepoStores.Stores(
                List.of(new RepoStores.Store(
                        "nexus.acme-0123456789ab", "private", "https://nexus.acme/maven", 2, 40, false)),
                null);
        var settings = new MavenSettingsRows.Rows(
                List.of(new MavenSettingsRows.File("/home/me/.m2/settings.xml", true)),
                List.of(new MavenSettingsRows.Mirror(
                        "nexus",
                        "*",
                        "https://nexus.acme/maven-public/",
                        List.of("jumpkick", "central", "google"),
                        null)),
                List.of(new MavenSettingsRows.Proxy("corp", "https", "proxy.acme:3128", List.of("*.acme"))),
                List.of(new MavenSettingsRows.Repository("acme-releases", "https://nexus.acme/releases/")),
                null);
        assertThat(DoctorCommand.reportJson(ok, ok, ok, ok, ok, ok, ok, 0, 0, 0, 0, 0, 0, null, one, repos, settings))
                .endsWith(
                        "\"workers\":[{\"artifact\":\"jk-image-builder\",\"version\":\"0.13.3\",\"source\":\"jk-local\","
                                + "\"jar\":\"/s/w.jar\",\"pom\":\"/s/w.pom\",\"declared\":2,\"classpath\":[\"/s/w.jar\"],\"error\":null,\"refused\":null,\"packagedBy\":\"ab12cd34ef56"
                                + "0".repeat(52) + "\"}],"
                                + "\"repos\":[{\"id\":\"nexus.acme-0123456789ab\",\"name\":\"private\",\"origin\":\"https://nexus.acme/maven\","
                                + "\"files\":2,\"bytes\":40,\"state\":\"ok\"}],"
                                + "\"settings\":{\"files\":[{\"path\":\"/home/me/.m2/settings.xml\",\"state\":\"read\"}],"
                                + "\"mirrors\":[{\"id\":\"nexus\",\"mirrorOf\":\"*\",\"url\":\"https://nexus.acme/maven-public/\","
                                + "\"captures\":[\"jumpkick\",\"central\",\"google\"],\"refusal\":null}],"
                                + "\"proxies\":[{\"id\":\"corp\",\"protocol\":\"https\",\"via\":\"proxy.acme:3128\",\"nonProxyHosts\":[\"*.acme\"]}],"
                                + "\"repositories\":[{\"id\":\"acme-releases\",\"url\":\"https://nexus.acme/releases/\"}]}}");
    }
}
