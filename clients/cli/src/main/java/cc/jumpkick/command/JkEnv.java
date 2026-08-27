// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.config.TomlScan;
import cc.jumpkick.jdk.DefaultGraalPolicy;
import cc.jumpkick.jdk.JdkHit;
import cc.jumpkick.jdk.JdkInventory;
import cc.jumpkick.jdk.JdkRegistry;
import cc.jumpkick.jdk.JdkResolution;
import cc.jumpkick.jdk.JdkVendor;
import cc.jumpkick.jdk.LockPinMatch;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.lock.ToolchainPins;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Desired {@code JAVA_HOME}/{@code GRAALVM_HOME}/{@code PATH} for a cwd (nearest project pin or
 * defaults). PATH is the live search path with only toolchain {@code bin} dirs swapped — never a
 * frozen snapshot — so neighbors like nvm stay put across prompts.
 */
public final class JkEnv {

    /** The environment variables {@code jk activate} manages. */
    public static final String JAVA_HOME = "JAVA_HOME";

    public static final String GRAALVM_HOME = "GRAALVM_HOME";
    public static final String PATH = "PATH";

    private final JdkRegistry registry;
    private final String basePath;
    private final String liveJavaHome;
    private final String liveGraalHome;
    private final JdkInventory globalDefault;

    public JkEnv(JdkRegistry registry, String basePath) {
        this(registry, basePath, JdkInventory.current(), null, null);
    }

    public JkEnv(JdkRegistry registry, String basePath, JdkInventory globalDefault) {
        this(registry, basePath, globalDefault, null, null);
    }

    public JkEnv(
            JdkRegistry registry,
            String basePath,
            JdkInventory globalDefault,
            String liveJavaHome,
            String liveGraalHome) {
        this.registry = registry;
        this.basePath = basePath == null ? "" : basePath;
        this.globalDefault = globalDefault;
        this.liveJavaHome = liveJavaHome;
        this.liveGraalHome = liveGraalHome;
    }

    /** Real-world entry: probe chain + live {@code PATH} / toolchain homes (never a frozen copy). */
    public static JkEnv defaults() {
        return new JkEnv(
                new JdkRegistry(),
                System.getenv("PATH"),
                JdkInventory.current(),
                System.getenv(JAVA_HOME),
                System.getenv(GRAALVM_HOME));
    }

    /**
     * Resolve the desired env for a {@code cwd} via the one canonical JDK order ({@link
     * cc.jumpkick.jdk.JdkResolution}): {@code JK_JDK} env, the project's {@code .jdk-version} /
     * {@code jk-lock.toml} / {@code jdk}, then the inventory default JDK, then {@code
     * JAVA_HOME} / {@code GRAALVM_HOME} / {@code PATH}. Never installs (the hook must not block the
     * shell). Empty only when nothing resolves. Carries JAVA_HOME / GRAALVM_HOME / PATH plus the
     * project root (when a {@code jk.toml} was found upstream).
     */
    public Target resolve(Path cwd) throws IOException {
        var root = findProjectRoot(cwd);
        // Shell-hook fast path: runs on every prompt — no engine call, no tomlj parse.
        // TomlScan reads only the JDK-resolution scalars; missing fields fail soft.
        var lockPins = root.isPresent() ? ToolchainPins.scan(root.get()) : ToolchainPins.NONE;
        String projectJdk = null;
        String projectGraal = null;
        int javaRelease = 0;
        if (root.isPresent()) {
            var scan = TomlScan.scan(root.get().resolve(ManifestPaths.MANIFEST), "jdk", "java", "native.graal");
            projectJdk = scan.get("jdk");
            javaRelease = scan.getInt("java", 0);
            // [native] present without an explicit graal spec defaults to "graalvm" —
            // same native.graal probe the engine uses for project-info native flags.
            projectGraal = scan.get("native.graal");
            if (projectGraal == null && scan.hasSection("native")) projectGraal = "graalvm";
        }
        var req = new JdkResolution.Request(
                root.orElse(cwd), /*switch*/
                null,
                System.getenv("JK_JDK"),
                lockPins.jdk(),
                projectJdk,
                javaRelease,
                System::getenv);
        var resolved = JdkResolution.resolveForHook(req, registry, globalDefault);
        if (resolved.jdk().isEmpty()) return Target.empty();
        var home = resolved.jdk().get().home();
        var jdk = new ResolvedJdk(home, matchVendor(home));
        return targetFor(root, jdk, resolveGraalHome(projectGraal, lockPins.graal()));
    }

    /**
     * Build the env vars (JAVA_HOME / GRAALVM_HOME / PATH). GRAALVM_HOME is managed independently of
     * JAVA_HOME from its own chain ({@code JK_GRAAL} > {@code graal} > the {@code jk jdk
     * graal} default); only when that chain finds nothing do we fall back to the active JDK if it is
     * itself a GraalVM. Absent → the hook unsets any GRAALVM_HOME it previously exported.
     *
     * <p>{@code PATH} keeps the caller's live entries and only swaps {@code JAVA_HOME/bin} (+
     * distinct {@code GRAALVM_HOME/bin} for {@code native-image}).
     */
    private Target targetFor(Optional<Path> root, ResolvedJdk jdk, Optional<Path> graalHome) {
        var vars = new LinkedHashMap<String, String>();
        var home = jdk.home();
        vars.put(JAVA_HOME, home.toString());
        String graal = null;
        if (graalHome.isPresent()) {
            graal = graalHome.get().toString();
            vars.put(GRAALVM_HOME, graal);
        } else if (isGraalvm(jdk)) {
            graal = home.toString();
            vars.put(GRAALVM_HOME, graal);
        }
        vars.put(PATH, ToolchainPath.swap(basePath, liveJavaHome, liveGraalHome, home.toString(), graal));
        return new Target(root, vars);
    }

    /**
     * Resolve the default GraalVM home, independent of JAVA_HOME: {@code JK_GRAAL} >
     * project {@code [native].graal} > lock {@code [graal]} > the {@code jk jdk graal} pointer >
     * {@link DefaultGraalPolicy}. The {@code native} keyword means "the preferred installed
     * GraalVM". Empty when no GraalVM is installed. An unsatisfied lock pin is a floor: later
     * defaults must still meet it.
     */
    private Optional<Path> resolveGraalHome(String projectGraalSpec, Lockfile.GraalPin lockGraal) {
        for (String spec : new String[] {System.getenv("JK_GRAAL"), projectGraalSpec}) {
            if (spec == null || spec.isBlank()) continue;
            if (spec.trim().equalsIgnoreCase("native")) {
                Optional<Path> g = defactoGraalHome(null);
                if (g.isPresent()) return g;
                continue;
            }
            Optional<JdkHit> hit = registry.findHitBySpec(spec).filter(DefaultGraalPolicy::isGraal);
            if (hit.isPresent()) return Optional.of(hit.get().home());
        }
        String graalFloor = null;
        if (lockGraal != null) {
            Optional<JdkHit> locked = LockPinMatch.chooseGraal(registry.listHits(), lockGraal);
            if (locked.isPresent()) return Optional.of(locked.get().home());
            // Nothing here satisfies a required vendor/version. Exporting some other GraalVM would
            // hand the shell a home the build itself will refuse; export none and let it install.
            if (lockGraal.hasRequirement()) return Optional.empty();
            graalFloor = lockGraal.suggestedVersion().isEmpty() ? null : lockGraal.suggestedVersion();
        }
        Optional<Path> ghome = globalDefault.graalHome();
        if (ghome.isPresent()
                && Files.isDirectory(ghome.get().resolve("bin"))
                && graalMeetsFloor(ghome.get(), graalFloor)) {
            return ghome;
        }
        Optional<String> gid = globalDefault.graalId();
        if (gid.isPresent()) {
            try {
                var m = registry.find(gid.get());
                if (m.isPresent() && graalMeetsFloor(m.get().home(), graalFloor)) {
                    return Optional.of(m.get().home());
                }
            } catch (IOException ignored) {
                // unreadable inventory — fall through to de-facto
            }
        }
        return defactoGraalHome(graalFloor);
    }

    private Optional<Path> defactoGraalHome(String graalFloor) {
        var hits = registry.listHits();
        if (graalFloor != null) {
            hits = hits.stream()
                    .filter(h -> LockPinMatch.meetsFloor(h.version(), graalFloor))
                    .toList();
        }
        return DefaultGraalPolicy.choose(hits).map(JdkHit::home);
    }

    private boolean graalMeetsFloor(Path home, String graalFloor) {
        if (graalFloor == null) return true;
        return LockPinMatch.hitFor(home, registry.listHits())
                .map(h -> LockPinMatch.meetsFloor(h.version(), graalFloor))
                .orElse(false);
    }

    /** Walk up from {@code cwd} until a {@code jk.toml} is found or root is reached. */
    static Optional<Path> findProjectRoot(Path cwd) {
        if (cwd == null) return Optional.empty();
        var p = cwd.toAbsolutePath().normalize();
        while (p != null) {
            if (Files.isRegularFile(p.resolve(ManifestPaths.MANIFEST))) return Optional.of(p);
            p = p.getParent();
        }
        return Optional.empty();
    }

    private JdkVendor matchVendor(Path home) {
        // Look first in the probe-emitted hits (cheap, already parsed); fall
        // back to reading the release file directly. Either way, UNKNOWN if
        // we can't tell — the GraalVM detection just won't fire.
        try {
            for (var hit : registry.listHits()) {
                if (hit.home().equals(home)) return hit.vendor();
            }
        } catch (RuntimeException ignored) {
            // listHits() may swallow IO already; fall through to release-file probe
        }
        try {
            return JdkVendor.fromRelease(home);
        } catch (RuntimeException ignored) {
            return JdkVendor.UNKNOWN;
        }
    }

    private static boolean isGraalvm(ResolvedJdk jdk) {
        var v = jdk.vendor();
        return v == JdkVendor.ORACLE_GRAALVM || v == JdkVendor.GRAALVM_CE;
    }

    public record ResolvedJdk(Path home, JdkVendor vendor) {}

    /**
     * Target env state for a given cwd.
     *
     * @param projectRoot present iff a {@code jk.toml} was found upstream
     * @param vars the env keys + values jk should ensure are set; an empty map means "no project —
     *     restore originals"
     */
    public record Target(Optional<Path> projectRoot, Map<String, String> vars) {

        public Target {
            vars = Map.copyOf(vars);
        }

        public static Target empty() {
            return new Target(Optional.empty(), Map.of());
        }

        public boolean isActive() {
            return !vars.isEmpty();
        }
    }
}
