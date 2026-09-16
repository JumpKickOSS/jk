// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.journal.BuildAccumulator;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.engine.journal.JkResultsMarkdown;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import cc.jumpkick.wire.runtime.ModulePlan;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end: a module whose compile fails on a missing symbol reaches {@code jk-results.md} with
 * javac's own {@code symbol:} and {@code location:} quoted in a repair hint under the diagnostic,
 * the record carries javac's key for the diagnostic, and the header sizes the file in tokens. The diagnostic travels the real path — the compiler
 * worker, the module plan, the journal accumulator — and only the write to disk is left out.
 */
@Tag("integration")
class ResultsHintE2eTest {

    private static final Pattern TOKENS = Pattern.compile("\ntokens ≈ (\\d+)\n");

    @Test
    void a_missing_symbol_carries_its_hint_and_the_header_sizes_the_file(@TempDir Path tmp) throws Exception {
        Path cache = TestCaches.dir("results-hint-cache");
        Path ws = workspace(tmp);

        Plans plans = new Plans();
        WorkspaceResult result = WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(ws, cache, null, 0, null, true, false, 1, null, false, true), plans);
        assertThat(result.success()).as("the build fails on the missing symbol").isFalse();

        BuildAccumulator acc = new BuildAccumulator("build", ws.toString(), "com.example:ws", "cli");
        for (Map.Entry<Path, BuildPlanResult> e : plans.byDir().entrySet()) {
            acc.addBuildPlan(e.getKey().toString(), e.getValue());
        }
        for (ModuleOutcome o : result.modules()) acc.addModule(o);
        acc.stamp(new JobOutcome.Failed(result.exitCode()));
        BuildRecord record = acc.toRecord(2_000, false, 1_000, "test", null);
        assertThat(record.diagnostics())
                .filteredOn(d -> d.code().equals("javac") && d.severity().equals("error"))
                .as("the compile worker's key rides the diagnostic into the record")
                .extracting(BuildRecord.Diag::key)
                .containsExactly("compiler.err.cant.resolve.location.args");
        String md = JkResultsMarkdown.render(record);

        assertThat(md).startsWith("# jk results — FAIL");
        assertThat(md).contains("cannot find symbol");
        assertThat(md)
                .contains("→ `method missing()` is not declared in `class com.example.Main` and not imported: "
                        + "fix the name, add the import, or `jk add` the dependency that provides it.");

        Matcher tokens = TOKENS.matcher(md);
        assertThat(tokens.find()).as("header carries a tokens line:\n" + md).isTrue();
        long estimate = Long.parseLong(tokens.group(1));
        assertThat(estimate).isCloseTo((long) Math.ceil(md.length() / 3.6), offset(1L));
    }

    /** Every module's finished plan, by module dir. */
    private static final class Plans implements WorkspaceBuildListener {
        private final Map<Path, BuildPlanResult> byDir = new LinkedHashMap<>();

        @Override
        public BuildPlanListener onModuleStart(ModulePlan module) {
            return new BuildPlanListener() {
                @Override
                public void planFinish(BuildPlanResult result) {
                    synchronized (Plans.this) {
                        byDir.put(module.dir(), result);
                    }
                }
            };
        }

        synchronized Map<Path, BuildPlanResult> byDir() {
            return Map.copyOf(byDir);
        }
    }

    /** One member with one class that calls a method nobody declared. */
    private static Path workspace(Path tmp) throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["app"]
                """);
        Path app = Files.createDirectories(ws.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                java    = 25
                """);
        Path src = Files.createDirectories(app.resolve("src/com/example"));
        Files.writeString(src.resolve("Main.java"), """
                package com.example;

                public final class Main {
                    void run() {
                        missing();
                    }
                }
                """);
        return ws;
    }
}
