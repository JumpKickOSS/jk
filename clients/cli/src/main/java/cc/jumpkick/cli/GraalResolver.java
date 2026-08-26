// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.Confirm;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.GraalLauncher;
import cc.jumpkick.http.Http;
import cc.jumpkick.jdk.DefaultGraalPolicy;
import cc.jumpkick.jdk.HostPlatform;
import cc.jumpkick.jdk.InstalledJdk;
import cc.jumpkick.jdk.JdkCatalog;
import cc.jumpkick.jdk.JdkCatalogClient;
import cc.jumpkick.jdk.JdkHit;
import cc.jumpkick.jdk.JdkInstaller;
import cc.jumpkick.jdk.JdkInventory;
import cc.jumpkick.jdk.JdkKeywords;
import cc.jumpkick.jdk.JdkRegistry;
import cc.jumpkick.jdk.JdkResolver;
import cc.jumpkick.jdk.JdkSelector;
import cc.jumpkick.jdk.LockPinMatch;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.ToolchainPins;
import cc.jumpkick.tool.NativeImageDriver;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the GraalVM home that owns {@code native-image} for {@code jk native} / native {@code jk
 * install}: explicit {@code [native].graal} pin (auto-install), else project JDK /
 * {@code $GRAALVM_HOME} / {@code PATH}, else prompt or install. Run before the progress UI; memoized
 * by spec. Where the launcher sits under a home — and which home a launcher belongs to — is
 * {@link GraalLauncher}'s answer, not this class's.
 */
public final class GraalResolver {

    private final Path jdksDir; // nullable — overrides the default jdks root
    private final boolean assumeYes; // --yes: install without prompting
    private final Map<String, Path> memo = new HashMap<>();

    public GraalResolver(Path jdksDir, boolean assumeYes) {
        this.jdksDir = jdksDir;
        this.assumeYes = assumeYes;
    }

    /**
     * The GraalVM home to use for {@code projectDir}, or empty when it couldn't be resolved (an
     * actionable message has already been printed — the caller should abort the native build). A
     * non-empty result is suitable to pass as {@code graalHome} to {@code NativePlans.nativeStep}.
     */
    public Optional<Path> resolve(Path projectDir, String graalSpec) {
        String key = graalSpec == null ? "" : graalSpec;
        if (memo.containsKey(key)) {
            return Optional.ofNullable(memo.get(key));
        }
        Path home = resolveUncached(projectDir, graalSpec);
        memo.put(key, home);
        return Optional.ofNullable(home);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private Path resolveUncached(Path projectDir, String graalSpec) {
        JdkRegistry registry = jdksDir != null ? new JdkRegistry(jdksDir) : new JdkRegistry();

        // 1. Explicit spec: --graal switch (jk.graal) > project.graal > JK_GRAAL env.
        String effective = firstNonBlank(SessionContext.current().graalSpec(), graalSpec, System.getenv("JK_GRAAL"));
        if (effective != null && !effective.isBlank()) {
            Optional<InstalledJdk> hit = registry.findBySpec(effective);
            if (hit.isPresent() && NativeImageDriver.resolve(hit.get().home()).isPresent()) {
                return hit.get().home();
            }
            return install(effective, registry, /*announce*/ "graal = \"" + effective + "\"");
        }

        // 2. Lock [graal] pin — ahead of the inventory pointer and de-facto policy,
        //    mirroring the JDK side's lock tier. Major-or-better among installed wins;
        //    an unsatisfied pin is a floor the native build must not sink below, so it
        //    installs the pinned spec rather than falling through to an older Graal.
        Lockfile.GraalPin lockGraal = ToolchainPins.scan(projectDir).graal();
        if (lockGraal != null) {
            Optional<JdkHit> locked =
                    LockPinMatch.chooseGraal(registry.listHits(), lockGraal.vendor(), lockGraal.version());
            if (locked.isPresent()
                    && NativeImageDriver.resolve(locked.get().home()).isPresent()) {
                return locked.get().home();
            }
            String spec = LockPinMatch.installSpec(lockGraal.vendor(), lockGraal.version());
            return install(
                    spec,
                    registry, /*announce*/
                    "[graal] " + lockGraal.vendor() + " " + lockGraal.version() + " (lock)");
        }

        // 3. The `jk jdk graal` default-graal pointer, if one is set and usable.
        try {
            JdkInventory gd = JdkInventory.current();
            Optional<Path> gh = gd.graalHome();
            if (gh.isPresent() && NativeImageDriver.resolve(gh.get()).isPresent()) {
                return gh.get();
            }
            Optional<String> gid = gd.graalId();
            if (gid.isPresent()) {
                Optional<InstalledJdk> byId = registry.find(gid.get());
                if (byId.isPresent()
                        && NativeImageDriver.resolve(byId.get().home()).isPresent()) {
                    return byId.get().home();
                }
            }
        } catch (IOException ignored) {
            // no usable default-graal — fall through to de-facto / ambient search
        }

        // 4. De-facto preferred installed Graal (same policy as the shell hook).
        Optional<JdkHit> defacto = DefaultGraalPolicy.choose(registry.listHits());
        if (defacto.isPresent()
                && NativeImageDriver.resolve(defacto.get().home()).isPresent()) {
            return defacto.get().home();
        }

        // 5. Ambient native-image search (project JDK → $GRAALVM_HOME → PATH).
        // projectJavaHome may be null (jk runs as a native image with no java.home,
        // and the project pins no JDK); NativeImageDriver.resolve tolerates null and
        // still checks $GRAALVM_HOME and PATH.
        Path projectJavaHome = null;
        try {
            projectJavaHome = JdkResolver.forProject(projectDir, jdksDir)
                    .map(InstalledJdk::home)
                    .orElse(null);
        } catch (IOException ignored) {
            // fall through with null
        }
        if (projectJavaHome == null) {
            String javaHome = System.getProperty("java.home");
            if (javaHome != null && !javaHome.isBlank()) projectJavaHome = Path.of(javaHome);
        }
        Optional<Path> binary = NativeImageDriver.resolve(projectJavaHome);
        if (binary.isPresent()) return graalHomeOf(binary.get(), projectJavaHome);

        // 6. Missing — offer Oracle GraalVM (prompt / --yes / non-TTY fail).
        return offerOracleGraalVm(projectJavaHome, registry);
    }

    /**
     * The GraalVM home that OWNS {@code launcher} — {@code NativePlans.nativeStep} wants the home,
     * and {@link NativeImageDriver#resolve} found the launcher.
     *
     * <p>This used to be a parent-of-parent at the call site, which is right for {@code
     * <home>/bin/native-image} and wrong for {@code <home>/lib/svm/bin/native-image.exe} — a path the
     * driver can and does return on Windows, and which the fixed depth turned into {@code
     * <home>/lib/svm}, a directory that is not a home. {@link GraalLauncher#homeOf} is the declared
     * inverse of the search that produced the path, so it knows both layouts and neither call site
     * has to.
     *
     * <p>{@code fallback} — the pinned JDK — is used when the launcher sits somewhere {@code
     * GraalLauncher} does not recognise, e.g. a bare {@code $PATH} directory that is not a GraalVM
     * {@code bin}. There is no home to name in that case, and {@code PlannerNative} re-searches
     * {@code $GRAALVM_HOME} and {@code $PATH} when the home it is handed turns out not to hold a
     * launcher.
     */
    static Path graalHomeOf(Path launcher, Path fallback) {
        return GraalLauncher.homeOf(launcher).orElse(fallback);
    }

    private Path offerOracleGraalVm(Path searchedJavaHome, JdkRegistry registry) {
        if (!assumeYes && !Confirm.isInteractiveTerminal()) {
            // Can't prompt — fail with the same actionable hint as the driver.
            CliOutput.err(NativeImageDriver.notFoundError(searchedJavaHome).getMessage());
            CliOutput.err("  Or pin a GraalVM with `graal = \"native\"` under [native], "
                    + "or pass --yes to install Oracle GraalVM automatically.");
            return null;
        }
        if (!assumeYes) {
            String warn = Theme.colorize(Glyphs.BANG, Theme.active().warning());
            boolean ok = Confirm.of(
                            warn + " native-image not found. " + "Install Oracle GraalVM to build native artifacts?",
                            true)
                    .ask();
            if (!ok) {
                CliOutput.err("Aborted — no GraalVM to build with. Pin one with "
                        + "`graal = \"native\"` or install: jk jdk install native");
                return null;
            }
        }
        return install("native", registry, /*announce*/ "Oracle GraalVM");
    }

    /** Resolve {@code spec} (keyword-aware) to a catalog entry and install it. */
    private Path install(String spec, JdkRegistry registry, String announce) {
        String os = HostPlatform.currentOs();
        String arch = HostPlatform.currentArch();
        if (!HostPlatform.supported()) {
            CliOutput.err("jk native: this host ("
                    + os
                    + "/"
                    + arch
                    + ") has no installable GraalVM; set $GRAALVM_HOME instead.");
            return null;
        }
        try {
            JdkCatalog catalog = new JdkCatalogClient().fetch();
            String effective = spec;
            if (JdkKeywords.isKeyword(spec)) {
                effective =
                        JdkKeywords.resolveToMajorSpec(catalog, spec, os, arch).orElse(spec);
            }
            Optional<JdkCatalog.Entry> entry = JdkSelector.selectPreferred(catalog, effective, os, arch);
            if (entry.isEmpty()) {
                CliOutput.err("jk native: no GraalVM matches " + spec + " on " + os + "/" + arch + ".");
                return null;
            }
            JdkCatalog.Entry e = entry.get();
            CliOutput.out(Theme.colorize("⬇", Theme.active().cyan())
                    + " Installing GraalVM "
                    + Theme.colorize(e.installFolderName(), Theme.active().focused())
                    + " ("
                    + announce
                    + ")…");
            InstalledJdk installed = new JdkInstaller(new Http(), registry).install(e);
            CliOutput.out(
                    Theme.colorize(Glyphs.CHECK, Theme.active().success()) + " GraalVM ready: " + installed.home());
            return installed.home();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            CliOutput.err("jk native: GraalVM install interrupted.");
            return null;
        } catch (Exception ex) {
            CliOutput.err("jk native: failed to install GraalVM (" + spec + "): " + ex.getMessage());
            return null;
        }
    }
}
