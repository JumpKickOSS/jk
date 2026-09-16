// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.compile.GroovycRequest;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceClasspath;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.runtime.base.CompileSupport;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.FreshnessStamp;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Writes a module's compile-groovy stamp the way {@code write-stamp-groovy} writes it: the token
 * lines of the request {@code PlannerLang.groovyRequest} derives, the toolchain digest and the
 * project release, so the forecast's stamp check — the build's own — accepts it. When the Groovy
 * toolchain cannot be resolved here, the stamp carries no tokens, which is the shape the
 * forecast's toolchain-less fallback reads.
 */
final class GroovyForecastStamps {

    private GroovyForecastStamps() {}

    static void writeBuildStamp(Path workspace, Path moduleDir, List<Path> groovySources, Cas cas) throws Exception {
        JkBuild project = JkBuildParser.parse(moduleDir.resolve("jk.toml"));
        BuildLayout layout = BuildLayout.of(workspace, moduleDir, project);
        Lockfile lock = LockfileReader.read(workspace.resolve("jk-lock.toml"));
        int release = project.project().javaRelease();
        Path javaHome = TaskForecaster.forecastJavaHome(moduleDir, project, lock);
        boolean compact = CompileSupport.isSimpleLayout(project.project(), moduleDir);
        boolean mixed =
                CompileSupport.resolveLanguages(project.project(), moduleDir).java();
        List<Path> inputs = groovySources;
        FreshnessStamp.ClasspathTokens tokens;
        try {
            WorkspaceClasspath.Result sib =
                    WorkspaceClasspath.resolve(moduleDir, project, Set.of(Scope.EXPORT, Scope.MAIN));
            List<Path> cp = PlannerSupport.mainCompileClasspath(project, lock, new ClasspathResolver(cas), sib, false);
            GroovycRequest req = PlannerLang.groovyRequest(
                    project,
                    lock,
                    moduleDir,
                    cas,
                    groovySources,
                    cp,
                    layout.groovyClassesDir(),
                    mixed ? PlannerKsp.kotlinJavaSourceRoots(true, compact, moduleDir, layout, null) : null,
                    mixed ? layout.groovyStubsDir() : null,
                    List.of(),
                    release,
                    javaHome);
            tokens = FreshnessStamp.ClasspathTokens.of(ActionKey.groovycClasspathTokens(req));
        } catch (Exception toolchainUnavailable) {
            FreshnessStamp.write(
                    layout.classesDir(), BuildStamps.GROOVY, "compile-groovy", "", inputs, List.of(), release, "");
            return;
        }
        FreshnessStamp.write(
                layout.classesDir(),
                BuildStamps.GROOVY,
                "compile-groovy",
                "",
                inputs,
                tokens,
                release,
                PlannerLang.groovyStampDigest(project, lock, moduleDir, release, javaHome));
    }
}
