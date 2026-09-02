// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Os;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * Resolve-or-install a project JDK for {@code jk sync} via {@link JdkResolution}. Missing pins
 * install from the JetBrains feed; bootstrap install becomes the default. Not subject to
 * {@code --force} — re-resolve by uninstalling explicitly.
 */
public final class JdkEnsure {

    public enum Source {
        ALREADY_PINNED,
        LOCKFILE_INSTALL,
        INSTALLED
    }

    public record Outcome(Optional<InstalledJdk> jdk, Source source, String specUsed) {
        public Outcome {
            Objects.requireNonNull(jdk, "jdk");
            Objects.requireNonNull(source, "source");
        }
    }

    private JdkEnsure() {}

    public static Outcome ensure(
            Path projectDir, Path jdksDirOverride, JkBuild build, Lockfile lock, Consumer<String> warn)
            throws IOException, InterruptedException {
        return ensure(projectDir, jdksDirOverride, build, lock, warn, true);
    }

    /**
     * As {@link #ensure(Path, Path, JkBuild, Lockfile, java.util.function.Consumer)}, but with an
     * explicit install permission. {@code allowInstall = false} is the resident engine's mode
     * ({@code jk sync} hosting): a JDK download must never happen silently inside the engine —
     * installs stay client-side ({@code jk jdk install}, or the sync command's pre-flight ensure),
     * per {@code docs/architecture.md} — so a resolution that would install instead fails with an
     * actionable message naming the client-side fix.
     */
    public static Outcome ensure(
            Path projectDir,
            Path jdksDirOverride,
            JkBuild build,
            Lockfile lock,
            Consumer<String> warn,
            boolean allowInstall)
            throws IOException, InterruptedException {
        return ensure(projectDir, jdksDirOverride, build, lock, warn, allowInstall, JdkInstallListener.NO_OP);
    }

    /**
     * As {@link #ensure(Path, Path, JkBuild, Lockfile, java.util.function.Consumer, boolean)}
     * with a progress sink for a missing-JDK install.
     */
    public static Outcome ensure(
            Path projectDir,
            Path jdksDirOverride,
            JkBuild build,
            Lockfile lock,
            Consumer<String> warn,
            boolean allowInstall,
            JdkInstallListener progress)
            throws IOException, InterruptedException {
        return ensure(
                projectDir,
                jdksDirOverride,
                (build != null && build.project() != null) ? build.project().jdk() : null,
                (build != null && build.project() != null) ? build.project().javaRelease() : 0,
                lock == null ? null : lock.jdk(),
                warn,
                allowInstall,
                progress);
    }

    /**
     * Scalar variant for thin-client callers holding an engine {@code ProjectInfo} rather than a
     * parsed model — {@code JdkEnsure} only ever read three values off the model: the project's
     * {@code jdk} spec, its {@code java} release floor, and the lock {@code [jdk]} pin.
     */
    public static Outcome ensure(
            Path projectDir,
            Path jdksDirOverride,
            String projectJdkSpec,
            int javaRelease,
            Lockfile.JdkPin lockJdk,
            Consumer<String> warn,
            boolean allowInstall)
            throws IOException, InterruptedException {
        return ensure(
                projectDir,
                jdksDirOverride,
                projectJdkSpec,
                javaRelease,
                lockJdk,
                warn,
                allowInstall,
                JdkInstallListener.NO_OP);
    }

    /**
     * As {@link #ensure(Path, Path, String, int, Lockfile.JdkPin, java.util.function.Consumer, boolean)}
     * with a progress sink for a missing-JDK install (TUI {@code downloading}/{@code installing}
     * labels). Already-on-disk resolution does not call {@code progress}.
     */
    public static Outcome ensure(
            Path projectDir,
            Path jdksDirOverride,
            String projectJdkSpec,
            int javaRelease,
            Lockfile.JdkPin lockJdk,
            Consumer<String> warn,
            boolean allowInstall,
            JdkInstallListener progress)
            throws IOException, InterruptedException {
        JdkRegistry registry = sharedRegistry(jdksDirOverride);
        JdkInventory defaults = JdkInventory.of(registry.jdksRoot());
        int latestLts = JdkLts.OFFLINE_LATEST_LTS;

        // Walk the one canonical resolution order (--jdk / JK_JDK / .jdk-version /
        // jk-lock.toml / project.jdk / project.java-floor / default / env / PATH).
        // The environment is the request's, never this process's: the engine is a daemon, so
        // System.getenv here would answer from whichever shell started it.
        UnaryOperator<String> env = projectDir != null ? BuildEnv.forModule(projectDir) : BuildEnv.ambient();
        JdkResolution.Request req = new JdkResolution.Request(
                projectDir,
                SessionContext.current().jdkSpec(),
                // null: the client folded JK_JDK into the switch tier before sending, and a
                // read here would be the daemon's environment.
                null,
                lockJdk,
                (projectJdkSpec == null || projectJdkSpec.isEmpty()) ? null : projectJdkSpec,
                javaRelease,
                env::apply);
        JdkResolution.Resolved r = JdkResolution.resolve(req, registry, defaults, latestLts);

        if (r.jdk().isPresent()) {
            return new Outcome(r.jdk(), mapSource(r.tier()), r.specUsed());
        }
        if (!r.wouldInstall()) {
            // Nothing pinned and nothing to install (resolution found no spec).
            return new Outcome(Optional.empty(), Source.ALREADY_PINNED, null);
        }
        if (!allowInstall) {
            // Engine-hosted sync: the client should have pre-flighted this install before sending
            // the request — reaching here means it didn't (or the lock was created engine-side with
            // a pin the client couldn't see). Fail structured rather than downloading silently.
            throw new IOException("JDK " + r.installSpec()
                    + " is not installed — run `jk jdk install " + r.installSpec()
                    + "` (or re-run `jk sync`; JDK installs stay client-side)");
        }

        // A named pin (or the bootstrap latest-LTS) isn't on disk — install it.
        String spec = r.installSpec();
        InstalledJdk installed = install(spec, registry, warn, progress);

        // A bootstrap install (no JDK was pinned or configured) becomes the
        // de-facto default — but only when no default is set yet, and only for
        // the bootstrap case (a named project pin must not hijack the global
        // default). r.tier() == DEFAULT marks the bootstrap path.
        if (r.tier() == JdkResolution.Tier.DEFAULT && defaults.defaultId().isEmpty()) {
            defaults.setDefault(installed);
        }
        return new Outcome(Optional.of(installed), Source.INSTALLED, spec);
    }

    /** Map a resolution tier to the coarse {@link Source} used for status wording. */
    private static Source mapSource(JdkResolution.Tier tier) {
        return tier == JdkResolution.Tier.LOCKFILE ? Source.LOCKFILE_INSTALL : Source.ALREADY_PINNED;
    }

    /**
     * Install exactly {@code spec} into the default registry, with no resolution walk and no
     * default-JDK side effects. The engine-host bootstrap uses this to satisfy jk's own runtime
     * floor without consulting (or disturbing) the user's project pins and global default.
     */
    public static InstalledJdk install(String spec, Consumer<String> warn) throws IOException, InterruptedException {
        return install(spec, new JdkRegistry(), warn, JdkInstallListener.NO_OP);
    }

    /**
     * One {@link JdkRegistry} per JDK root, for the life of the process.
     *
     * <p>The registry's hit-list memo is a per-instance field, and this method used to construct a
     * fresh instance on every call — so the memo never survived, and {@code ensure} is called
     * <em>per module</em>. Each construction is a cold eleven-probe host scan (jk dir, SDKMAN, mise,
     * IntelliJ, {@code $JAVA_HOME}, {@code PATH}, plus a {@code release} file parse per candidate),
     * about 65 metadata operations. Sixty-two constructions in a workspace build is ~4,000 stats for
     * facts that cannot change mid-build. A per-instance memo on an object built per call is not a
     * missed optimisation, it is a bug the resident engine makes permanent.
     *
     * <p>Sharing is safe because the registry already has the invalidation hook this needs:
     * {@link JdkRegistry#refresh()} drops the memo after an install or uninstall, and because the
     * instance is now shared that drop is seen by every later caller instead of only the one that
     * happened to hold it.
     */
    private static JdkRegistry sharedRegistry(@Nullable Path jdksDirOverride) {
        Path root = jdksDirOverride != null ? jdksDirOverride : JkDirs.jdks();
        return REGISTRIES.computeIfAbsent(
                root.toAbsolutePath().normalize(),
                r -> jdksDirOverride != null ? new JdkRegistry(r) : new JdkRegistry());
    }

    private static final ConcurrentMap<Path, JdkRegistry> REGISTRIES = new ConcurrentHashMap<>();

    /** Test seam: forget every shared registry, so the next ensure re-probes the host. */
    public static void resetSharedRegistries() {
        REGISTRIES.clear();
    }

    private static InstalledJdk install(
            String spec, JdkRegistry registry, Consumer<String> warn, JdkInstallListener progress)
            throws IOException, InterruptedException {
        if (!HostPlatform.supported()) {
            throw new IOException("host "
                    + Os.name()
                    + "/"
                    + System.getProperty("os.arch")
                    + " is not covered by the JetBrains JDK feed (set JAVA_HOME explicitly)");
        }
        JdkCatalog catalog = new JdkCatalogClient().onWarning(warn).fetch();
        // selectPreferred biases a vendor-unqualified spec to jk's vendor order
        // and resolves range specs (">=26") to the lowest available major.
        Optional<JdkCatalog.Entry> entry =
                JdkSelector.selectPreferred(catalog, spec, HostPlatform.currentOs(), HostPlatform.currentArch());
        if (entry.isEmpty()) {
            throw new IOException(
                    "no JDK matches " + spec + " on " + HostPlatform.currentOs() + "/" + HostPlatform.currentArch());
        }
        JdkCatalog.Entry chosen = entry.get();
        String name = JdkProgressLabel.compactName(chosen);
        JdkInstallListener sink = progress == null ? JdkInstallListener.NO_OP : progress;
        JdkInstallListener named = new JdkInstallListener() {
            @Override
            public void onDownloadStart(String ignored, long totalBytes) {
                sink.onDownloadStart(name, totalBytes);
            }

            @Override
            public void onDownloadProgress(long readBytes, long totalBytes) {
                sink.onDownloadProgress(readBytes, totalBytes);
            }

            @Override
            public void onExtractStart(String ignored) {
                sink.onExtractStart(name);
            }

            @Override
            public void onInstalled(InstalledJdk jdk) {
                sink.onInstalled(jdk);
            }

            @Override
            public void onAlreadyInstalled(InstalledJdk jdk) {
                sink.onAlreadyInstalled(jdk);
            }
        };
        return new JdkService().install(chosen, registry, false, named);
    }
}
