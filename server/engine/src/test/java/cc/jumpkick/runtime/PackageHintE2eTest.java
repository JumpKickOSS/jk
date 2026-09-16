// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.journal.BuildAccumulator;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.engine.journal.JkResultsMarkdown;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end: a module declares a library under {@code [test-dependencies]} and its main source
 * imports one of the library's packages. The {@code package does not exist} the main compile
 * reports reaches {@code jk-results.md} with the coordinate the lock provides it under, so the
 * hint's {@code jk add} has its argument.
 *
 * <p>Network: jspecify and the junit launcher pin come from Maven Central into the cache under
 * {@code build/}, which persists across runs so repeats are warm.
 */
@Tag("integration")
class PackageHintE2eTest {

    @Test
    void the_hint_names_the_lock_row_that_provides_the_package(@TempDir Path tmp) throws Exception {
        Path project = write(tmp.resolve("scoped"));

        BuildPlanResult result = build(project, Files.createDirectories(Path.of("build/test-cache/package-hint")));
        assertThat(result.success())
                .as("the main compile fails on the missing package")
                .isFalse();

        BuildAccumulator acc = new BuildAccumulator("build", project.toString(), "com.example:scoped", "cli");
        acc.addBuildPlan(project.toString(), result);
        acc.stamp(new JobOutcome.Failed(1));
        BuildRecord record = acc.toRecord(2_000, false, 1_000, "test", null);

        assertThat(record.diagnostics())
                .filteredOn(d -> d.key().equals("compiler.err.doesnt.exist"))
                .as("the missing-package error carries its provider")
                .extracting(BuildRecord.Diag::message)
                .anySatisfy(
                        m -> assertThat(m)
                                .contains("package org.jspecify.annotations does not exist")
                                .contains(
                                        "provided by: org.jspecify:jspecify (in the lock, not on this module's compile classpath)"));

        String md = JkResultsMarkdown.render(record);
        assertThat(md)
                .contains("→ package `org.jspecify.annotations` is provided by `org.jspecify:jspecify` "
                        + "(in the lock, not on this module's compile classpath): `jk add org.jspecify:jspecify` "
                        + "in this module, or fix the import.");
    }

    private static Path write(Path project) throws Exception {
        Files.createDirectories(project);
        Files.writeString(project.resolve("jk.toml"), """
                name    = "scoped"
                group   = "com.example"
                version = "1.0.0"
                java    = 25

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"

                [test-dependencies]
                jspecify = "org.jspecify:jspecify:1.0.0"
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "6.1.3" }
                """);
        Path src = Files.createDirectories(project.resolve("src/main/java/com/example"));
        Files.writeString(src.resolve("Main.java"), """
                package com.example;

                import org.jspecify.annotations.Nullable;

                public final class Main {
                    @Nullable String name;
                }
                """);
        return project;
    }

    private static BuildPlanResult build(Path project, Path cache) throws Exception {
        var build = JkBuildParser.parse(project.resolve("jk.toml"));
        BuildPlan lock = LockPlans.lockBuildPlan(
                project, build, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        BuildPlanResult lockResult = lock.run();
        assertThat(lockResult.errors()).isEmpty();

        BuildPlanner.Inputs in = new BuildPlanner.Inputs(
                project,
                cache,
                project.resolve("jk.toml"),
                project.resolve("jk-lock.toml"),
                project,
                1,
                0,
                null,
                null,
                true,
                false,
                false,
                false,
                Set.of(),
                SessionContext.current());
        return BuildPlanner.fullPlan(in).run();
    }
}
