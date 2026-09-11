// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.androidsdk.AndroidRepoFeed;
import cc.jumpkick.androidsdk.AndroidSdk;
import cc.jumpkick.androidsdk.AndroidSdkInstaller;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.WorkspaceMerge;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.testing.SysProps;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Optional Now-in-Android workspace sweep. Builds every module under a local NiA clone that
 * carries a {@code jk.toml} and prints a pass/fail inventory — exploration harness, not CI.
 *
 * <p>Set {@code JK_NIA_ROOT} to the clone path and {@code JK_NIA_SCRATCH=1} to enable.
 *
 * <p>The assertions here can only be exercised on a machine with the NiA clone: no gate sets the
 * enabling variables, so proving the sweep goes red on a broken module means running it there with
 * a module forced to fail. In-gate, this class is compile-checked only.
 */
@EnabledIfEnvironmentVariable(
        named = "JK_NIA_SCRATCH",
        matches = "1",
        disabledReason = "optional NiA marathon; export JK_NIA_SCRATCH=1 and JK_NIA_ROOT=/path/to/nowinandroid")
@Tag("slow")
@ExtendWith(SysProps.class)
class NiaScratchTest {

    private static final Path NIA = Path.of(System.getenv().getOrDefault("JK_NIA_ROOT", "/tmp/nowinandroid"));

    @Test
    void sweep_all_jk_modules() throws Exception {
        Path cache = Path.of(System.getProperty("user.dir"), "build", "android-spike-cache");
        Path sdkRoot = Path.of(System.getProperty("user.dir"), "build", "android-spike-sdk");
        System.setProperty(AndroidSdk.ROOT_PROPERTY, sdkRoot.toString());
        acceptLicenses();

        // Dependency order comes from the root [workspace] modules list — a plain walk sorts
        // alphabetically (data before database) and breaks a clean rebuild.
        List<Path> modules = new ArrayList<>();
        JkBuild root = JkBuildParser.parse(NIA.resolve("jk.toml"));
        for (String rel : requireNonNull(root.workspace()).modules()) {
            Path dir = NIA.resolve(rel);
            if (Files.isRegularFile(dir.resolve("jk.toml"))) modules.add(dir);
        }
        System.out.println("NIA-SWEEP: " + modules.size() + " module(s)");
        List<String> failures = new ArrayList<>();
        for (Path module : modules) {
            String name = NIA.relativize(module).toString();
            try {
                String failure = buildOne(module);
                if (failure != null) failures.add(name + " — " + failure);
                System.out.println(failure == null ? "NIA-PASS: " + name : "NIA-FAIL: " + name + " — " + failure);
                if (failure != null && Files.isDirectory(module.resolve("target"))) {
                    try (var walk = Files.walk(module.resolve("target"), 4)) {
                        walk.filter(Files::isDirectory)
                                .forEach(p -> System.out.println("NIA-TREE: " + module.relativize(p)));
                    }
                }
            } catch (Throwable t) {
                failures.add(name + " — threw " + t);
                System.out.println("NIA-FAIL: " + name + " — threw " + t);
            }
        }
        assertThat(modules)
                .as("no jk modules under %s — check JK_NIA_ROOT points at a locked NiA clone", NIA)
                .isNotEmpty();
        assertThat(failures)
                .as("%d of %d NiA module(s) failed:%n%s", failures.size(), modules.size(), String.join("\n", failures))
                .isEmpty();
    }

    /** The release finish: jk build --release of :app → R8 full mode + a signed AAB. */
    @Test
    void app_release_aab() throws Exception {
        Path module = NIA.resolve("app");
        Path cache = Path.of(System.getProperty("user.dir"), "build", "android-spike-cache");
        Path sdkRoot = Path.of(System.getProperty("user.dir"), "build", "android-spike-sdk");
        System.setProperty(AndroidSdk.ROOT_PROPERTY, sdkRoot.toString());
        acceptLicenses();

        Path keystore = Path.of(System.getProperty("user.dir"), "build", "nia-release.jks");
        if (!Files.isRegularFile(keystore)) {
            Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
            new ProcessBuilder(
                            keytool.toString(),
                            "-genkeypair",
                            "-keystore",
                            keystore.toString(),
                            "-storepass",
                            "rel-store-pass",
                            "-keypass",
                            "rel-key-pass",
                            "-alias",
                            "upload",
                            "-keyalg",
                            "RSA",
                            "-keysize",
                            "2048",
                            "-validity",
                            "30",
                            "-dname",
                            "CN=nia")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor();
        }

        JkBuild build = JkBuildParser.parse(module.resolve("jk.toml"));
        var rootManifest = JkBuildParser.parse(NIA.resolve("jk.toml"));
        var modules = WorkspaceLoader.loadModules(NIA, rootManifest);
        build = WorkspaceMerge.applyToModule(rootManifest, build, modules.values());
        BuildPlan lock =
                LockPlans.lockBuildPlan(module, build, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        BuildPlanResult lockResult = lock.run();
        System.out.println("NIA-RELEASE lock: " + lockResult.errors());
        assertThat(lockResult.errors()).as("app lock").isEmpty();

        BuildPlanner.Inputs in = new BuildPlanner.Inputs(
                        module,
                        cache,
                        module.resolve("jk.toml"),
                        module.resolve("jk-lock.toml"),
                        module,
                        1,
                        0,
                        null,
                        null,
                        true,
                        false,
                        false,
                        false,
                        Set.of(),
                        SessionContext.current())
                .withVariant(
                        "release|contentType=demo",
                        Map.of(
                                "RELEASE_KEYSTORE", keystore.toAbsolutePath().toString(),
                                "RELEASE_STORE_PASSWORD", "rel-store-pass",
                                // PKCS12: the key password IS the store password (keytool
                                // ignores -keypass for PKCS12 stores).
                                "RELEASE_KEY_PASSWORD", "rel-store-pass"));
        BuildPlanResult result = BuildPlanner.fullPlan(in).run();
        System.out.println("NIA-RELEASE diags: " + result.errors());
        System.out.println("NIA-RELEASE success: " + result.success());
        List<Path> artifacts;
        try (var walk = Files.walk(module.resolve("target"))) {
            artifacts = walk.filter(
                            p -> p.toString().endsWith(".aab") || p.toString().endsWith(".apk"))
                    .toList();
        }
        artifacts.forEach(p -> System.out.println("NIA-RELEASE artifact: " + module.relativize(p)));
        assertThat(result.success())
                .as("release build of :app, diags %s", result.errors())
                .isTrue();
        assertThat(artifacts)
                .as("signed AAB under %s", module.resolve("target"))
                .anyMatch(p -> p.toString().endsWith(".aab"));
    }

    /** Null on success, else the first diagnostic. */
    private static @Nullable String buildOne(Path module) throws Exception {
        Path cache = Path.of(System.getProperty("user.dir"), "build", "android-spike-cache");
        JkBuild build = JkBuildParser.parse(module.resolve("jk.toml"));
        // Workspace context for the lock, exactly LockFlow's module branch: resolve
        // workspace:* placeholders against the sibling list (the build side self-discovers).
        if (!build.isWorkspaceRoot()) {
            var rootOpt = WorkspaceLocator.findRoot(module);
            if (rootOpt.isPresent()) {
                JkBuild rootManifest = JkBuildParser.parse(rootOpt.get().resolve("jk.toml"));
                var modules = WorkspaceLoader.loadModules(rootOpt.get(), rootManifest);
                build = WorkspaceMerge.applyToModule(rootManifest, build, modules.values());
            }
        }
        BuildPlan lock =
                LockPlans.lockBuildPlan(module, build, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        BuildPlanResult lockResult = lock.run();
        if (!lockResult.errors().isEmpty())
            return "lock: " + lockResult.errors().getFirst();
        BuildPlanner.Inputs in = new BuildPlanner.Inputs(
                module,
                cache,
                module.resolve("jk.toml"),
                module.resolve("jk-lock.toml"),
                module,
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
        // Flavored modules build the demo variant (the flavor NiA's own CI exercises — no
        // backend needed). True workspace variant propagation (the app's selection reaching
        // sibling AAR builds automatically) is a recorded follow-up; the sweep selects
        // per-module.
        if (Files.readString(module.resolve("jk.toml")).contains("[variants.contentType]")
                || module.getFileName().toString().equals("app")) {
            in = in.withVariant("contentType=demo", Map.of());
        }
        BuildPlanResult result = BuildPlanner.fullPlan(in).run();
        if (!result.errors().isEmpty()) return "build: ALL-DIAGS " + result.errors();
        if (!result.success()) return "build: failed without diagnostics";
        return null;
    }

    private static void acceptLicenses() throws Exception {
        var sdk = AndroidSdk.resolve();
        var installer = new AndroidSdkInstaller(sdk);
        if (!sdk.installed("platforms;android-34")) {
            for (var license : installer.feed().licenses().entrySet()) {
                sdk.recordLicense(license.getKey(), AndroidRepoFeed.licenseHash(license.getValue()));
            }
        }
    }
}
