// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.ClasspathFingerprint;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * package-jar forecast keys must match {@link BuildPipelines} packaging tokens
 * (including empty {@code sbom:} for libraries) so a warm jar is not permanently "repackage".
 */
class BuildPlanForecastPackageKeyTest {

    @Test
    void library_package_tokens_include_empty_sbom_like_the_build(@TempDir Path tmp) throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        Path classFile = classes.resolve("t/Lib.class");
        Files.createDirectories(classFile.getParent());
        Files.writeString(classFile, "fake");

        Path jar = tmp.resolve("lib.jar");
        String mainClass = "";
        Map<String, String> manifest = Map.of();
        byte[] sbom = null; // libraries: no application SBOM

        // BuildPipelines.packageJarStep tokens (library path).
        List<String> buildTokens = List.of(
                "classes:" + ClasspathFingerprint.entry(classes),
                "main:" + mainClass,
                "sbom:" + (sbom == null ? "" : cc.jumpkick.util.Hashing.sha256Hex(sbom)),
                "manifest:" + manifest);

        // Forecast tokens after fix (must stay in lockstep with the build).
        List<String> forecastTokens = List.of(
                "classes:" + ClasspathFingerprint.entry(classes),
                "main:" + mainClass,
                "sbom:" + (sbom == null ? "" : cc.jumpkick.util.Hashing.sha256Hex(sbom)),
                "manifest:" + manifest);

        assertThat(forecastTokens).isEqualTo(buildTokens);
        // Pre-fix tokens (missing sbom:) produce a different action key — the bug we fixed.
        List<String> broken =
                List.of("classes:" + ClasspathFingerprint.entry(classes), "main:" + mainClass, "manifest:" + manifest);
        String task = ActionKey.qualifiedTaskId("package-jar", jar);
        String good = ActionKey.forArtifact(task, BuildIdentity.cacheKeyVersion(), buildTokens);
        String bad = ActionKey.forArtifact(task, BuildIdentity.cacheKeyVersion(), broken);
        assertThat(good).isNotEqualTo(bad);
    }

    @Test
    void estimate_eta_is_zero_when_plan_is_fully_cached(@TempDir Path tmp) {
        // Empty plan modules → 0; fully-cached modules skipped in estimateEtaMillis.
        ExplainPlan empty = new ExplainPlan(List.of(), Map.of(), 1, List.of());
        long eta = BuildService.estimateEtaMillis(
                empty, tmp, tmp.resolve("cache"), 1, null, null, false, false, true, false);
        assertThat(eta).isZero();
    }

    @Test
    void assembly_forecast_tokens_match_build_pipelines_recipe(@TempDir Path tmp) throws Exception {
        // Regression: forecast used whole-lock RUNTIME + all sibling lock RUNTIME jars while
        // assemblyStep (JK-1345) uses ModuleRuntimeClasspath — permanent "repackage" on explain.
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        Path classFile = classes.resolve("App.class");
        Files.writeString(classFile, "fake");
        Path dep = tmp.resolve("dep.jar");
        Files.writeString(dep, "dep-bytes");

        List<Path> depJars = List.of(dep);
        String main = "com.example.Main";
        Map<String, String> manifest = Map.of();

        List<String> buildTokens = List.of(
                "classes:" + ClasspathFingerprint.entry(classes),
                "deps:" + ClasspathFingerprint.of(depJars),
                "main:" + main,
                "manifest:" + manifest,
                "packaging:fat");

        // Forecast path: same deps set (assemblyDependencyJars) + fingerprintDepJars when jars exist.
        String depsTok = BuildPlanForecast.fingerprintDepJars(depJars, null, Map.of());
        List<String> forecastTokens = List.of(
                "classes:" + ClasspathFingerprint.entry(classes),
                "deps:" + depsTok,
                "main:" + main,
                "manifest:" + manifest,
                "packaging:fat");

        assertThat(forecastTokens).isEqualTo(buildTokens);

        Path assemblyJar = tmp.resolve("app-all.jar");
        String task = ActionKey.qualifiedTaskId("package-assembly", assemblyJar);
        String good = ActionKey.forArtifact(task, BuildIdentity.cacheKeyVersion(), buildTokens);
        // Old whole-workspace-style deps set (extra unrelated jar) must not share the key.
        Path extra = tmp.resolve("workspace-noise.jar");
        Files.writeString(extra, "noise");
        List<String> oldStyle = List.of(
                "classes:" + ClasspathFingerprint.entry(classes),
                "deps:" + ClasspathFingerprint.of(List.of(dep, extra)),
                "main:" + main,
                "manifest:" + manifest,
                "packaging:fat");
        String bad = ActionKey.forArtifact(task, BuildIdentity.cacheKeyVersion(), oldStyle);
        assertThat(good).isNotEqualTo(bad);
    }
}
