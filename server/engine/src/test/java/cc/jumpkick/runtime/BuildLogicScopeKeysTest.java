// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildLogicFixtures.expectedKey;
import static cc.jumpkick.runtime.BuildLogicFixtures.scaffold;
import static cc.jumpkick.runtime.BuildLogicFixtures.writeStampGroovy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.runtime.base.BuildLogicAnchor;
import cc.jumpkick.task.ActionCache;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The two scopes a standalone project's build logic keys on, held apart within one build. */
@Tag("integration")
class BuildLogicScopeKeysTest {

    /**
     * A standalone runs a module stem and a guard stem over one directory in one build, and each
     * keys on its own scope: the module stem on the module's inputs, the guard stem on the whole
     * checkout. A file outside the module's source roots and manifest moves the guard key alone.
     */
    @Test
    void a_standalone_keys_its_module_stem_and_its_guard_stem_on_their_own_scopes(@TempDir Path dir) throws Exception {
        Path project = scaffold(dir);
        Files.createDirectories(project.resolve(".jk"));
        Path moduleScript = project.resolve(".jk/after-resources.groovy");
        writeStampGroovy(moduleScript);
        Path guardScript = project.resolve(".jk/guard.groovy");
        Files.writeString(guardScript, "outDir.resolve('verdict.txt').toFile().text = 'clean'\n");

        ActionCache ac = new ActionCache(new Cas(dir.resolve("cache/cas")), dir.resolve("cache/actions"));
        BuildLayout layout = BuildLayout.of(project, JkBuildParser.parse(project.resolve("jk.toml")));
        Path classes = Files.createDirectories(layout.classesDir());
        BuildLogicScope scope = BuildLogicScope.of(project);

        runBoth(project, layout, ac, classes, scope, new BuildLogicInputTokens(), new StringBuilder());
        List<Path> scripts = List.of(moduleScript, guardScript);
        String moduleKey = expectedKey(
                project,
                scripts,
                moduleScript,
                BuildLogicAnchor.AFTER_RESOURCES,
                BuildLogicSupport.projectInputTokens(project));
        String guardKey = expectedKey(
                project, scripts, guardScript, BuildLogicAnchor.GUARD, BuildLogicSupport.workspaceInputTokens(project));
        assertTrue(ac.lookup(moduleKey).isPresent(), "the module stem keys on the module's inputs");
        assertTrue(ac.lookup(guardKey).isPresent(), "the guard stem keys on the whole checkout");

        Files.writeString(project.resolve("NOTES.md"), "outside every source root\n");
        StringBuilder labels = new StringBuilder();
        runBoth(project, layout, ac, classes, scope, new BuildLogicInputTokens(), labels);
        String moduleKeyAfter = expectedKey(
                project,
                scripts,
                moduleScript,
                BuildLogicAnchor.AFTER_RESOURCES,
                BuildLogicSupport.projectInputTokens(project));
        String guardKeyAfter = expectedKey(
                project, scripts, guardScript, BuildLogicAnchor.GUARD, BuildLogicSupport.workspaceInputTokens(project));
        assertEquals(moduleKey, moduleKeyAfter, "a file outside the module scope leaves the module key alone");
        assertNotEquals(guardKey, guardKeyAfter, "and moves the guard key");
        assertTrue(ac.lookup(guardKeyAfter).isPresent(), "the guard stem re-ran and recorded under the new key");
        assertThat(labels.toString())
                .contains("build-logic:after-resources: cache hit")
                .doesNotContain("build-logic:guard: cache hit");
    }

    /** The two anchors a standalone runs over its one directory, sharing the build's token holder. */
    private static void runBoth(
            Path project,
            BuildLayout layout,
            ActionCache ac,
            Path classes,
            BuildLogicScope scope,
            BuildLogicInputTokens tokens,
            StringBuilder labels)
            throws Exception {
        assertTrue(BuildLogicSupport.run(
                project,
                layout,
                ac,
                classes,
                BuildLogicAnchor.AFTER_RESOURCES,
                scope,
                s -> labels.append(s).append(';'),
                line -> {},
                tokens,
                () -> false));
        assertTrue(BuildLogicSupport.run(
                project,
                layout,
                ac,
                null,
                BuildLogicAnchor.GUARD,
                scope,
                s -> labels.append(s).append(';'),
                line -> {},
                tokens,
                () -> false));
    }
}
