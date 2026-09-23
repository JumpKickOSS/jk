// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.audit.AuditReport;
import cc.jumpkick.config.SecretRedactor;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The bytes the event factories produced before they became records, spelled out. The wire is frozen
 * pre-1.0: a record that reorders a field, changes a default or drops the leading schema is a
 * different line to every client that decodes it, and the round-trip contract cannot see that.
 */
class ProtoEventsFrozenBytesTest {
    @Test
    void plan_burst_and_preflight_lines() {
        assertThat(ProtoEvents.preflight("resolve", 2, 5, "libs"))
                .isEqualTo("{\"type\":\"preflight\",\"stage\":\"resolve\",\"done\":2,\"total\":5,\"label\":\"libs\"}");
        assertThat(ProtoEvents.invocationPhase("plan", "start"))
                .isEqualTo("{\"type\":\"invocation-phase\",\"phase\":\"plan\",\"status\":\"start\"}");
        assertThat(ProtoEvents.planModule("a/b", "g:a", "build", 7, true))
                .isEqualTo(
                        "{\"type\":\"plan-module\",\"dir\":\"a/b\",\"coord\":\"g:a\",\"planName\":\"build\",\"weight\":7,\"fullyCached\":true}");
        assertThat(ProtoEvents.planStep("a/b", "compile-java", "Compile", "compile"))
                .isEqualTo(
                        "{\"type\":\"plan-task\",\"dir\":\"a/b\",\"name\":\"compile-java\",\"label\":\"Compile\",\"stage\":\"compile\"}");
        assertThat(ProtoEvents.planDone(12)).isEqualTo("{\"type\":\"plan-done\",\"count\":12}");
        assertThat(ProtoEvents.moduleStart("a/b")).isEqualTo("{\"type\":\"module-start\",\"dir\":\"a/b\"}");
    }

    @Test
    void eta_lines_carry_millis_twice_and_the_full_wall_only_when_known() {
        assertThat(ProtoEvents.eta(1500)).isEqualTo("{\"type\":\"eta\",\"millis\":1500,\"remainingMs\":1500}");
        assertThat(ProtoEvents.eta(1500, 9000))
                .isEqualTo("{\"type\":\"eta\",\"millis\":1500,\"remainingMs\":1500,\"fullMillis\":9000}");
    }

    @Test
    void the_hot_events_lead_with_the_schema() {
        assertThat(ProtoEvents.stepStart("a/b", "compile-java", "compile", 40))
                .isEqualTo(
                        "{\"schema\":1,\"type\":\"task-start\",\"dir\":\"a/b\",\"task\":\"compile-java\",\"stage\":\"compile\",\"ticks\":40}");
        assertThat(ProtoEvents.progress("a/b", "compile-java", 3, 50, 200, 9, 2, false))
                .isEqualTo(
                        "{\"schema\":1,\"type\":\"progress\",\"dir\":\"a/b\",\"task\":\"compile-java\",\"delta\":3,\"numerator\":50,\"denominator\":200,\"progress\":25,\"tasksTotal\":9,\"tasksComplete\":2,\"cancelled\":false}");
        assertThat(ProtoEvents.tickUpdate("a/b", "run-tests", 1, 1, 3, 9, 8, true))
                .isEqualTo(
                        "{\"schema\":1,\"type\":\"tick-update\",\"dir\":\"a/b\",\"task\":\"run-tests\",\"delta\":1,\"numerator\":1,\"denominator\":3,\"progress\":33.3,\"tasksTotal\":9,\"tasksComplete\":8,\"cancelled\":true}");
        assertThat(ProtoEvents.progress("a/b", "x", 0, 0, 0, 1, 0, false)).contains("\"progress\":null,");
        assertThat(ProtoEvents.label("a/b", "run-tests", "3 of 9"))
                .isEqualTo(
                        "{\"schema\":1,\"type\":\"label\",\"dir\":\"a/b\",\"task\":\"run-tests\",\"label\":\"3 of 9\"}");
        assertThat(ProtoEvents.output("a/b", "run-tests", "he said \"hi\"\n"))
                .isEqualTo(
                        "{\"schema\":1,\"type\":\"output\",\"dir\":\"a/b\",\"task\":\"run-tests\",\"line\":\"he said \\\"hi\\\"\\n\"}");
        assertThat(ProtoEvents.planStart("a/b", "build", 0, 200, 9, 0, false))
                .isEqualTo(
                        "{\"schema\":1,\"type\":\"buildplan-start\",\"dir\":\"a/b\",\"planName\":\"build\",\"numerator\":0,\"denominator\":200,\"progress\":0,\"tasksTotal\":9,\"tasksComplete\":0,\"cancelled\":false}");
        assertThat(ProtoEvents.workspaceProgress("a/b", 3, 4, "execute", 1, 2))
                .isEqualTo(
                        "{\"schema\":1,\"type\":\"workspace-progress\",\"dir\":\"a/b\",\"numerator\":3,\"denominator\":4,\"progress\":75,\"phase\":\"execute\",\"modulesComplete\":1,\"modulesTotal\":2,\"remainingMs\":-1,\"R0\":0}");
    }

    @Test
    void task_finish_carries_wall_and_wait() {
        assertThat(ProtoEvents.stepFinish("a/b", "compile-java", "compile", "SUCCESS", 1200, 300))
                .isEqualTo(
                        "{\"type\":\"task-finish\",\"dir\":\"a/b\",\"task\":\"compile-java\",\"stage\":\"compile\",\"status\":\"SUCCESS\",\"millis\":1200,\"waitMillis\":300}");
    }

    @Test
    void diagnostics_omit_the_additive_fields_that_say_nothing_and_serialize_the_stack_once_last() {
        assertThat(ProtoEvents.warn("a/b", "compile-java", "W1", "unchecked"))
                .isEqualTo(
                        "{\"type\":\"warn\",\"dir\":\"a/b\",\"task\":\"compile-java\",\"code\":\"W1\",\"message\":\"unchecked\"}");
        assertThat(ProtoEvents.errorLine("a/b", "run-tests", "E1", "boom", "FooTest.bar", "java.lang.AssertionError"))
                .isEqualTo(
                        "{\"type\":\"error-line\",\"dir\":\"a/b\",\"task\":\"run-tests\",\"code\":\"E1\",\"message\":\"boom\",\"test\":\"FooTest.bar\",\"exceptionClass\":\"java.lang.AssertionError\"}");
        assertThat(ProtoEvents.errorLine("a/b", "run-tests", "E1", "boom", "", ""))
                .isEqualTo(
                        "{\"type\":\"error-line\",\"dir\":\"a/b\",\"task\":\"run-tests\",\"code\":\"E1\",\"message\":\"boom\"}");
        assertThat(ProtoEvents.errorLine(
                        "a/b", "run-tests", "E1", "boom", "m", "junit", "FooTest", "bar", "AE", "at x\n"))
                .isEqualTo(
                        "{\"type\":\"error-line\",\"dir\":\"a/b\",\"task\":\"run-tests\",\"code\":\"E1\",\"message\":\"boom\",\"module\":\"m\",\"engine\":\"junit\",\"testClass\":\"FooTest\",\"method\":\"bar\",\"exceptionClass\":\"AE\",\"stack\":\"at x\\n\"}");
        TestFailureInfo failure = new TestFailureInfo(
                "m",
                "junit",
                "FooTest",
                "bar",
                "AE",
                "expected 1",
                "at x",
                3,
                "src/FooTest.java",
                42,
                40,
                List.of("a", "b"));
        assertThat(ProtoEvents.planDiagnostic("a/b", "run-tests", "E1", "", failure))
                .isEqualTo(
                        "{\"type\":\"buildplan-diagnostic\",\"dir\":\"a/b\",\"task\":\"run-tests\",\"code\":\"E1\",\"message\":\"expected 1\",\"module\":\"m\",\"engine\":\"junit\",\"testClass\":\"FooTest\",\"method\":\"bar\",\"exceptionClass\":\"AE\",\"file\":\"src/FooTest.java\",\"line\":42,\"snippetStart\":40,\"worker\":3,\"snippet\":[\"a\",\"b\"],\"stack\":\"at x\"}");
        assertThat(ProtoEvents.planDiagnostic("a/b", "run-tests", "E1", "own message", failure))
                .contains("\"message\":\"own message\"");
        assertThat(ProtoEvents.planDiagnostic("a/b", "run-tests", "E1", "boom", "t", "AE"))
                .isEqualTo(
                        "{\"type\":\"buildplan-diagnostic\",\"dir\":\"a/b\",\"task\":\"run-tests\",\"code\":\"E1\",\"message\":\"boom\",\"test\":\"t\",\"exceptionClass\":\"AE\"}");
        assertThat(ProtoEvents.planDiagnostic(
                        "a/b",
                        "compile-java",
                        "javac",
                        "cannot find symbol",
                        "",
                        "",
                        "compiler.err.cant.resolve.location"))
                .isEqualTo(
                        "{\"type\":\"buildplan-diagnostic\",\"dir\":\"a/b\",\"task\":\"compile-java\",\"code\":\"javac\",\"key\":\"compiler.err.cant.resolve.location\",\"message\":\"cannot find symbol\"}");
        assertThat(PlanDiagnosticEvent.decode(ProtoEvents.planDiagnostic(
                                "a/b",
                                "compile-java",
                                "javac",
                                "cannot find symbol",
                                "",
                                "",
                                "compiler.err.doesnt.exist"))
                        .key())
                .isEqualTo("compiler.err.doesnt.exist");
    }

    @Test
    void plan_finish_shapes_carry_their_kind_and_only_their_own_fields() {
        assertThat(ProtoEvents.planFinish("a/b", true))
                .isEqualTo(
                        "{\"type\":\"buildplan-finish\",\"kind\":\"build\",\"dir\":\"a/b\",\"success\":true,\"cancelled\":false}");
        assertThat(ProtoEvents.planFinish("a/b", false, true))
                .isEqualTo(
                        "{\"type\":\"buildplan-finish\",\"kind\":\"build\",\"dir\":\"a/b\",\"success\":false,\"cancelled\":true}");
        assertThat(ProtoEvents.planFinish("a/b", true, 10, 8, 1, 1))
                .isEqualTo(
                        "{\"type\":\"buildplan-finish\",\"kind\":\"build\",\"dir\":\"a/b\",\"success\":true,\"buildOutcome\":null,\"tests\":{\"total\":10,\"succeeded\":8,\"failed\":1,\"skipped\":1}}");
        assertThat(ProtoEvents.planFinish("a/b", true, "up-to-date", -1, 0, 0, 0))
                .isEqualTo(
                        "{\"type\":\"buildplan-finish\",\"kind\":\"build\",\"dir\":\"a/b\",\"success\":true,\"buildOutcome\":\"up-to-date\"}");
        assertThat(ProtoEvents.planFinishLock("a/b", true, 210, 7, 3, 2, 4, List.of("mirror")))
                .isEqualTo(
                        "{\"type\":\"buildplan-finish\",\"kind\":\"lock\",\"dir\":\"a/b\",\"success\":true,\"lockPackages\":210,\"lockChanged\":7,\"lockSources\":3,\"lockPlugins\":2,\"lockUnverified\":4,\"lockInsecure\":[\"mirror\"]}");
        assertThat(ProtoEvents.planFinishSync("a/b", true, 4, 206))
                .isEqualTo(
                        "{\"type\":\"buildplan-finish\",\"kind\":\"sync\",\"dir\":\"a/b\",\"success\":true,\"syncFetched\":4,\"syncUpToDate\":206}");
        assertThat(ProtoEvents.planFinishFormat("a/b", true, 16, 1))
                .isEqualTo(
                        "{\"type\":\"buildplan-finish\",\"kind\":\"format\",\"dir\":\"a/b\",\"success\":true,\"formatTotal\":16,\"formatWorkerExit\":1}");
        assertThat(ProtoEvents.planFinishGitFetch("a/b", false, null, null))
                .isEqualTo(
                        "{\"type\":\"buildplan-finish\",\"kind\":\"git-fetch\",\"dir\":\"a/b\",\"success\":false,\"gitCheckout\":null,\"gitSha\":null}");
        assertThat(ProtoEvents.planFinishPublish("a/b", true, 7))
                .isEqualTo(
                        "{\"type\":\"buildplan-finish\",\"kind\":\"publish\",\"dir\":\"a/b\",\"success\":true,\"publishFiles\":7}");
        assertThat(ProtoEvents.planFinishPublish("a/b", true, 7, List.of("/w/target/sbom/a-1.cdx.json")))
                .isEqualTo(
                        "{\"type\":\"buildplan-finish\",\"kind\":\"publish\",\"dir\":\"a/b\",\"success\":true,\"publishFiles\":7,\"publishWritten\":[\"/w/target/sbom/a-1.cdx.json\"]}");
        assertThat(ProtoEvents.planFinishImport("a/b", false, 2, 1, "bad pom"))
                .isEqualTo(
                        "{\"type\":\"buildplan-finish\",\"kind\":\"import\",\"dir\":\"a/b\",\"success\":false,\"importExit\":2,\"importWarnings\":1,\"importError\":\"bad pom\"}");
        assertThat(ProtoEvents.planFinishImage("a/b", true, 3, 3, 0, 0, "ghcr.io/x:1", null, "x", "1", null))
                .isEqualTo(
                        "{\"type\":\"buildplan-finish\",\"kind\":\"image\",\"dir\":\"a/b\",\"success\":true,\"tests\":{\"total\":3,\"succeeded\":3,\"failed\":0,\"skipped\":0},\"imageRef\":\"ghcr.io/x:1\",\"imageTarball\":null,\"imageName\":\"x\",\"imageVersion\":\"1\",\"imageDaemonExe\":null}");
        assertThat(ProtoEvents.planFinishImage("a/b", false, -1, 0, 0, 0, null, null, null, null, null))
                .isEqualTo(
                        "{\"type\":\"buildplan-finish\",\"kind\":\"image\",\"dir\":\"a/b\",\"success\":false,\"imageRef\":null,\"imageTarball\":null,\"imageName\":null,\"imageVersion\":null,\"imageDaemonExe\":null}");
    }

    @Test
    void lock_audit_format_import_and_provision_lines() {
        assertThat(ProtoEvents.lockModule("a/b", "g:a"))
                .isEqualTo("{\"type\":\"lock-module\",\"dir\":\"a/b\",\"coord\":\"g:a\"}");
        assertThat(ProtoEvents.lockPackage("a/b", "g:x", "1.2"))
                .isEqualTo("{\"type\":\"lock-package\",\"dir\":\"a/b\",\"name\":\"g:x\",\"version\":\"1.2\"}");
        assertThat(ProtoEvents.lockPackage(null, "g:x", null, 0))
                .isEqualTo("{\"type\":\"lock-package\",\"dir\":null,\"name\":\"g:x\",\"version\":null,\"total\":0}");
        assertThat(ProtoEvents.lockPhase("a/b", "Downloading 3 artifacts…"))
                .isEqualTo("{\"type\":\"lock-phase\",\"dir\":\"a/b\",\"label\":\"Downloading 3 artifacts…\"}");
        assertThat(ProtoEvents.lockFinish(false, 6, List.of("no such artifact", "x"), -1))
                .isEqualTo(
                        "{\"type\":\"lock-finish\",\"success\":false,\"exitCode\":6,\"errors\":[\"no such artifact\",\"x\"],\"refreshed\":-1}");
        assertThat(ProtoEvents.auditFinding(
                        "a/b", new AuditReport.Finding("g:x", "1.2", "GHSA-1", "bad", AuditReport.Severity.HIGH, null)))
                .isEqualTo(
                        "{\"type\":\"audit-finding\",\"dir\":\"a/b\",\"package\":\"g:x\",\"version\":\"1.2\",\"id\":\"GHSA-1\",\"severity\":\"HIGH\",\"summary\":\"bad\",\"fixedIn\":null,\"ignoreReason\":null,\"ignoreUntil\":null,\"ignoreExpired\":false}");
        assertThat(ProtoEvents.auditFinding(
                        "a/b",
                        new AuditReport.Finding(
                                "g:x",
                                "1.2",
                                "GHSA-1",
                                "bad",
                                AuditReport.Severity.HIGH,
                                "1.3",
                                new AuditReport.Ignore("test only", LocalDate.of(2026, 12, 31), true))))
                .isEqualTo(
                        "{\"type\":\"audit-finding\",\"dir\":\"a/b\",\"package\":\"g:x\",\"version\":\"1.2\",\"id\":\"GHSA-1\",\"severity\":\"HIGH\",\"summary\":\"bad\",\"fixedIn\":\"1.3\",\"ignoreReason\":\"test only\",\"ignoreUntil\":\"2026-12-31\",\"ignoreExpired\":true}");
        assertThat(ProtoEvents.formatFile("a/b", "src/A.java", "changed", null, 1, 16))
                .isEqualTo(
                        "{\"type\":\"format-file\",\"dir\":\"a/b\",\"path\":\"src/A.java\",\"status\":\"changed\",\"message\":null,\"index\":1,\"total\":16}");
        assertThat(ProtoEvents.importNote("a/b", "warn", "skipped profile"))
                .isEqualTo("{\"type\":\"import-note\",\"dir\":\"a/b\",\"kind\":\"warn\",\"text\":\"skipped profile\"}");
        assertThat(ProtoEvents.provisionResult("/bin/mvn", "3.9", "downloaded", null, null, 0))
                .isEqualTo(
                        "{\"type\":\"provision-result\",\"bin\":\"/bin/mvn\",\"version\":\"3.9\",\"source\":\"downloaded\",\"verification\":null,\"error\":null,\"exit\":0}");
    }

    @Test
    void module_and_workspace_terminals() {
        assertThat(ProtoEvents.moduleFinish("a/b", "g:a", true, 0, 1200))
                .isEqualTo(
                        "{\"type\":\"module-finish\",\"dir\":\"a/b\",\"coord\":\"g:a\",\"success\":true,\"exitCode\":0,\"millis\":1200,\"didWork\":true,\"cancelled\":false}");
        assertThat(ProtoEvents.moduleFinish("a/b", "g:a", false, 1, 5, false, true))
                .isEqualTo(
                        "{\"type\":\"module-finish\",\"dir\":\"a/b\",\"coord\":\"g:a\",\"success\":false,\"exitCode\":1,\"millis\":5,\"didWork\":false,\"cancelled\":true}");
        assertThat(ProtoEvents.moduleFinish(
                        "a/b",
                        "g:a",
                        true,
                        0,
                        9,
                        true,
                        false,
                        new ModuleOutcome.Image("ghcr.io/x:1", null, "x", null, null),
                        null))
                .isEqualTo(
                        "{\"type\":\"module-finish\",\"dir\":\"a/b\",\"coord\":\"g:a\",\"success\":true,\"exitCode\":0,\"millis\":9,\"didWork\":true,\"cancelled\":false,\"imageRef\":\"ghcr.io/x:1\",\"imageName\":\"x\",\"hasImage\":true}");
        assertThat(ProtoEvents.moduleFinish(
                        "a/b", "g:a", true, 0, 9, true, false, null, new ModuleOutcome.Shelved("g:a:1", "aa", "bb")))
                .isEqualTo(
                        "{\"type\":\"module-finish\",\"dir\":\"a/b\",\"coord\":\"g:a\",\"success\":true,\"exitCode\":0,\"millis\":9,\"didWork\":true,\"cancelled\":false,\"shelfCoord\":\"g:a:1\",\"shelfJarSha256\":\"aa\",\"shelfPomSha256\":\"bb\"}");
        assertThat(ProtoEvents.workspaceFinish(
                        false, 2, SecretRedactor.of(List.of("s3cret")).redactAll(List.of("m1", "m2")), true))
                .isEqualTo(
                        "{\"type\":\"workspace-finish\",\"success\":false,\"exitCode\":2,\"errors\":[\"m1\",\"m2\"],\"cancelled\":true}");
        assertThat(ProtoEvents.withCancelled(ProtoEvents.planFinish("a/b", true, "up-to-date", -1, 0, 0, 0), true))
                .isEqualTo(
                        "{\"type\":\"buildplan-finish\",\"kind\":\"build\",\"dir\":\"a/b\",\"success\":true,\"buildOutcome\":\"up-to-date\",\"cancelled\":true}");
    }
}
