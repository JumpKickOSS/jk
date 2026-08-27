// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import cc.jumpkick.discovery.ToolHealth;
import cc.jumpkick.lock.Lockfile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

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
            Path projectDir,
            String switchSpec,
            String envSpec,
            Lockfile.JdkPin lockJdk,
            String projectJdkSpec,
            int projectJavaRelease,
            Function<String, String> env) {
        public Request {
            if (env == null) env = k -> null;
        }
    }

    public record Resolved(
            Optional<InstalledJdk> jdk, Tier tier, String specUsed, boolean wouldInstall, String installSpec) {

        static Resolved found(InstalledJdk jdk, Tier tier, String spec) {
            return new Resolved(Optional.of(jdk), tier, spec, false, null);
        }

        static Resolved install(Tier tier, String spec) {
            return new Resolved(Optional.empty(), tier, spec, true, spec);
        }

        public static final Resolved NONE = new Resolved(Optional.empty(), Tier.NONE, null, false, null);
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
            // Nothing anywhere clears the lock's floor — install it rather than build under it.
            if (canInstall && lockFloor != null) {
                return Resolved.install(Tier.LOCKFILE, LockPinMatch.installSpec(req.lockJdk()));
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
    private static Resolved named(String spec, Tier tier, JdkRegistry reg, boolean canInstall, String lockFloor) {
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

    private static Resolved jdkVersionFile(Path dir, JdkRegistry reg, boolean canInstall) {
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
    private static Resolved lockfile(Lockfile.JdkPin pin, JdkRegistry reg, boolean canInstall) {
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

    private static Resolved envHome(String home, Tier tier, String lockFloor) {
        if (home == null || home.isBlank()) return null;
        Path p = Path.of(home);
        if (!hasBin(p) || !meetsFloor(p, lockFloor)) return null;
        return Resolved.found(installed(p), tier, null);
    }

    /**
     * Whether an ambient home clears the lock's floor. No registry knows these homes, so the
     * version comes off their release file; an unreadable one cannot be shown to clear a floor.
     */
    private static boolean meetsFloor(Path home, String lockFloor) {
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
