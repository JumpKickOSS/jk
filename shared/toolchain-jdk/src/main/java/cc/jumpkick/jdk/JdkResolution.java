// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import cc.jumpkick.discovery.ToolHealth;
import cc.jumpkick.lock.JdkPin;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Canonical JDK resolution shared by the build plan and {@code jk activate}. Order: {@code --jdk},
 * {@code JK_JDK}, {@code .jdk-version}, lock, {@code jdk}, java-release floor, the inventory default,
 * {@code JAVA_HOME}/{@code GRAALVM_HOME}, then {@code PATH}. {@link #resolve} stops on an
 * uninstalled named pin with {@code wouldInstall}; {@link #resolveForHook} never installs and falls through.
 */
public final class JdkResolution {

    public enum Tier {
        SWITCH,
        JK_ENV,
        JDK_VERSION_FILE,
        LOCKFILE,
        PROJECT_TOML,
        JAVA_RELEASE_FLOOR,
        DEFAULT,
        JAVA_HOME,
        GRAALVM_HOME,
        PATH,
        NONE
    }

    /** Resolution inputs. Nullable fields mean "tier not applicable". */
    public record Request(
            @Nullable Path projectDir,
            @Nullable String switchSpec,
            @Nullable String envSpec,
            @Nullable JdkPin lockJdk,
            @Nullable String projectJdkSpec,
            int projectJavaRelease,
            Function<String, @Nullable String> env) {
        // Spelled out rather than compact: the canonical constructor a compact form synthesizes
        // reaches the class file without the type-argument annotation on {@code env}, and every
        // caller compiled against it would read the lookup as never null.
        public Request(
                @Nullable Path projectDir,
                @Nullable String switchSpec,
                @Nullable String envSpec,
                @Nullable JdkPin lockJdk,
                @Nullable String projectJdkSpec,
                int projectJavaRelease,
                Function<String, @Nullable String> env) {
            this.projectDir = projectDir;
            this.switchSpec = switchSpec;
            this.envSpec = envSpec;
            this.lockJdk = lockJdk;
            this.projectJdkSpec = projectJdkSpec;
            this.projectJavaRelease = projectJavaRelease;
            this.env = env;
        }
    }

    public record Resolved(
            @Nullable InstalledJdk jdk,
            Tier tier,
            @Nullable String specUsed,
            boolean wouldInstall,
            @Nullable String installSpec) {

        static Resolved found(InstalledJdk jdk, Tier tier, @Nullable String spec) {
            return new Resolved(jdk, tier, spec, false, null);
        }

        static Resolved install(Tier tier, String spec) {
            return new Resolved(null, tier, spec, true, spec);
        }

        public Optional<InstalledJdk> jdkOpt() {
            return Optional.ofNullable(jdk);
        }

        public static final Resolved NONE = new Resolved(null, Tier.NONE, null, false, null);
    }

    private JdkResolution() {}

    /**
     * Build-time resolution: an uninstalled named pin yields {@code wouldInstall}, and the ambient
     * {@code JAVA_HOME}/{@code GRAALVM_HOME}/{@code PATH} are valid last-resort tiers (a build must
     * find <em>some</em> JDK).
     */
    public static Resolved resolve(Request req, JdkRegistry registry, JdkInventory defaults, int latestLtsMajor) {
        return walk(req, registry, defaults, latestLtsMajor, true, true);
    }

    /**
     * Shell-hook resolution: never installs; only pin or default (no ambient {@code JAVA_HOME}/
     * {@code PATH} fallback).
     */
    public static Resolved resolveForHook(Request req, JdkRegistry registry, JdkInventory defaults) {
        return walk(req, registry, defaults, JdkLts.OFFLINE_LATEST_LTS, false, false);
    }

    private static Resolved walk(
            Request req,
            JdkRegistry reg,
            JdkInventory defaults,
            int latestLtsMajor,
            boolean canInstall,
            boolean envFallback) {
        Resolved r;
        if ((r = named(req.switchSpec(), Tier.SWITCH, reg, canInstall, null)) != null) return r;
        if ((r = named(req.envSpec(), Tier.JK_ENV, reg, canInstall, null)) != null) return r;
        if ((r = jdkVersionFile(req.projectDir(), reg, canInstall)) != null) return r;
        if ((r = lockfile(req.lockJdk(), reg, canInstall)) != null) return r;
        // An unsatisfied lock suggestion is a floor, not a skip — on the build path as much as the
        // hook. Later tiers may only pick a JDK that still meets it (do not build a 25 lock on 21).
        String lockFloor =
                req.lockJdk() == null || req.lockJdk().suggestedVersion().isEmpty()
                        ? null
                        : req.lockJdk().suggestedVersion();
        if ((r = named(req.projectJdkSpec(), Tier.PROJECT_TOML, reg, canInstall, lockFloor)) != null) return r;

        // project.java floor: only when nothing is explicitly pinned and the
        // requested language level is newer than the latest LTS — then we need a
        // JDK at least that new (e.g. java = 26 when the latest LTS is 25).
        if ((req.projectJdkSpec() == null || req.projectJdkSpec().isBlank())
                && latestLtsMajor > 0
                && req.projectJavaRelease() > latestLtsMajor) {
            if ((r = named(">=" + req.projectJavaRelease(), Tier.JAVA_RELEASE_FLOOR, reg, canInstall, lockFloor))
                    != null) {
                return r;
            }
        }

        List<JdkHit> hits = reg.listHits();
        List<JdkHit> pool = lockFloor == null ? hits : meetingFloor(hits, lockFloor);

        // default: the exact recorded home wins (unambiguous when two installs
        // share a vendor-major identifier), then the recorded identifier, then
        // the de-facto policy.
        Optional<Path> defHome = defaults.defaultHome();
        if (defHome.isPresent() && hasBin(defHome.get()) && inPool(defHome.get(), pool)) {
            return Resolved.found(installed(defHome.get()), Tier.DEFAULT, null);
        }
        Optional<String> defId = defaults.defaultId();
        if (defId.isPresent()) {
            try {
                Optional<InstalledJdk> d = reg.find(defId.get());
                if (d.isPresent() && inPool(d.get().home(), pool)) {
                    return Resolved.found(d.get(), Tier.DEFAULT, defId.get());
                }
            } catch (IOException ignored) {
                // unreadable registry — fall through to the de-facto policy
            }
        }
        Optional<JdkHit> defacto = DefaultJdkPolicy.choose(pool, latestLtsMajor);
        if (defacto.isPresent()) {
            return Resolved.found(installed(defacto.get().home()), Tier.DEFAULT, null);
        }

        // Ambient JAVA_HOME / GRAALVM_HOME / PATH — only for the build path; the
        // shell hook must not re-export the shell's own JDK as a jk activation.
        if (envFallback) {
            if ((r = envHome(req.env().apply("JAVA_HOME"), Tier.JAVA_HOME, lockFloor)) != null) return r;
            if ((r = envHome(req.env().apply("GRAALVM_HOME"), Tier.GRAALVM_HOME, lockFloor)) != null) return r;
            Optional<Path> onPath = ActiveJavac.home();
            if (onPath.isPresent() && hasBin(onPath.get()) && meetsFloor(onPath.get(), lockFloor)) {
                return Resolved.found(installed(onPath.get()), Tier.PATH, null);
            }
            // Nothing anywhere clears the lock's floor. A real vendor/major is an install;
            // an unknown-vendor suggestion is poison from a dropped manifest pin — settle
            // on whatever is already installed instead of `no JDK matches nosuchvendor-99`.
            JdkPin lockPin = req.lockJdk();
            if (canInstall && lockFloor != null && lockPin != null) {
                if (LockPinMatch.suggestionIsInstallable(lockPin)) {
                    return Resolved.install(Tier.LOCKFILE, LockPinMatch.installSpec(lockPin));
                }
                Optional<JdkHit> settled = DefaultJdkPolicy.choose(hits, latestLtsMajor);
                if (settled.isPresent()) {
                    return Resolved.found(installed(settled.get().home()), Tier.DEFAULT, null);
                }
                if ((r = envHome(req.env().apply("JAVA_HOME"), Tier.JAVA_HOME, null)) != null) return r;
                if ((r = envHome(req.env().apply("GRAALVM_HOME"), Tier.GRAALVM_HOME, null)) != null) {
                    return r;
                }
                Optional<Path> pathHome = ActiveJavac.home();
                if (pathHome.isPresent() && hasBin(pathHome.get())) {
                    return Resolved.found(installed(pathHome.get()), Tier.PATH, null);
                }
            }
            // Nothing on disk at all → bootstrap-install the latest LTS (which
            // then becomes the default).
            if (canInstall && latestLtsMajor > 0 && reg.listHits().isEmpty()) {
                return Resolved.install(Tier.DEFAULT, "temurin-" + latestLtsMajor);
            }
        }
        return Resolved.NONE;
    }

    /** A named-spec tier: resolve on disk; else (build) signal install, else (hook) continue. */
    private static @Nullable Resolved named(
            @Nullable String spec, Tier tier, JdkRegistry reg, boolean canInstall, @Nullable String lockFloor) {
        if (spec == null || spec.isBlank()) return null;
        Optional<InstalledJdk> hit = reg.findBySpec(spec);
        if (hit.isPresent()) {
            if (lockFloor != null && !inPool(hit.get().home(), meetingFloor(reg.listHits(), lockFloor))) {
                return null;
            }
            return Resolved.found(hit.get(), tier, spec);
        }
        return canInstall ? Resolved.install(tier, spec) : null;
    }

    private static @Nullable Resolved jdkVersionFile(@Nullable Path dir, JdkRegistry reg, boolean canInstall) {
        if (dir == null) return null;
        Optional<String> pin;
        try {
            pin = JdkResolver.readJdkVersion(dir);
        } catch (IOException e) {
            return null;
        }
        if (pin.isEmpty()) return null;
        String spec;
        try {
            spec = JdkResolver.validatePin(pin.get());
        } catch (IllegalArgumentException e) {
            return null; // malformed .jdk-version → skip this tier
        }
        return named(spec, Tier.JDK_VERSION_FILE, reg, canInstall, null);
    }

    /**
     * Lock {@code [jdk]} pin. A {@code required-*} field must be matched exactly; a suggestion is
     * only a floor on the major. Build: unsatisfied → would install. Hook: unsatisfied → fall
     * through with a major floor, unless the pin states a requirement, which a fall-through would
     * quietly ignore.
     */
    private static @Nullable Resolved lockfile(@Nullable JdkPin pin, JdkRegistry reg, boolean canInstall) {
        if (pin == null) return null;
        Optional<JdkHit> hit = LockPinMatch.choose(reg.listHits(), pin);
        if (hit.isPresent()) {
            return Resolved.found(installed(hit.get().home()), Tier.LOCKFILE, LockPinMatch.installSpec(pin));
        }
        // A requirement is not negotiable: install it rather than settle for something installed.
        // A suggestion is only a floor, so an unmet one falls through — later tiers are held to
        // that floor and install only if nothing anywhere clears it.
        if (canInstall && pin.hasRequirement()) {
            return Resolved.install(Tier.LOCKFILE, LockPinMatch.installSpec(pin));
        }
        return null;
    }

    private static List<JdkHit> meetingFloor(List<JdkHit> hits, String lockedVersion) {
        List<JdkHit> out = new ArrayList<>();
        for (JdkHit h : hits) {
            if (LockPinMatch.meetsFloor(h.version(), lockedVersion)) out.add(h);
        }
        return out;
    }

    private static boolean inPool(Path home, List<JdkHit> pool) {
        return LockPinMatch.hitFor(home, pool).isPresent();
    }

    private static @Nullable Resolved envHome(@Nullable String home, Tier tier, @Nullable String lockFloor) {
        if (home == null || home.isBlank()) return null;
        Path p = Path.of(home);
        if (!hasBin(p) || !meetsFloor(p, lockFloor)) return null;
        return Resolved.found(installed(p), tier, null);
    }

    /**
     * Whether an ambient home clears the lock's floor. No registry knows these homes, so the
     * version comes off their release file; an unreadable one cannot be shown to clear a floor.
     */
    private static boolean meetsFloor(Path home, @Nullable String lockFloor) {
        if (lockFloor == null) return true;
        return ToolHealth.javaVersion(home)
                .map(v -> LockPinMatch.meetsFloor(v, lockFloor))
                .orElse(false);
    }

    /**
     * Accept only homes that can compile: a {@code bin/} tree with {@code javac}. Package-manager
     * JREs under {@code /usr/lib/jvm} often have {@code bin/java} (and a {@code release} file) but no
     * compiler — those must not win current/default/JAVA_HOME tiers.
     */
    private static boolean hasBin(Path home) {
        return home != null && Files.isDirectory(home.resolve("bin")) && ToolHealth.hasJavac(home);
    }

    private static InstalledJdk installed(Path home) {
        return new InstalledJdk(JdkRegistry.identifierFor(home), home);
    }
}
