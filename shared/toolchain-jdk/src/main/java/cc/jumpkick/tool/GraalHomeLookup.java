// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.tool;

import cc.jumpkick.host.GraalLauncher;
import cc.jumpkick.jdk.DefaultGraalPolicy;
import cc.jumpkick.jdk.InstalledJdk;
import cc.jumpkick.jdk.JdkHit;
import cc.jumpkick.jdk.JdkInventory;
import cc.jumpkick.jdk.JdkRegistry;
import cc.jumpkick.jdk.LockPinMatch;
import cc.jumpkick.lock.GraalPin;
import cc.jumpkick.lock.ToolchainPins;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The installed GraalVM a native build should link with, found without installing or asking.
 *
 * <p>One policy for both callers. The CLI's {@code GraalResolver} runs these tiers before it
 * offers to install what is missing; the engine runs them for a request that shipped no client
 * answer — a build submitted over HTTP or MCP — so a workspace with an always-native member links
 * against the Graal these tiers name, never the daemon's own {@code $GRAALVM_HOME} or whichever
 * JDK happens to be pinned. The tiers, in order:
 *
 * <ol>
 *   <li>an explicit spec ({@code --graal}, {@code [native].graal}, {@code JK_GRAAL}) — installed
 *       or nothing, since another Graal does not satisfy a named one;
 *   <li>the lock's {@code [graal]} pin — major-or-better among installed, and an unsatisfied pin is
 *       a floor the build must not sink below, so it too answers nothing rather than an older Graal;
 *   <li>the {@code jk jdk graal} default pointer;
 *   <li>the de-facto preferred installed Graal ({@link DefaultGraalPolicy}).
 * </ol>
 *
 * <p>Every answer is checked for a {@code native-image} launcher IN THE HOME ITSELF, through
 * {@link GraalLauncher#in} — no environment is consulted, because none can answer the question
 * being asked. These tiers name a specific installed Graal, and {@link NativeImageDriver#resolve},
 * which was doing the checking, falls back to {@code $GRAALVM_HOME} and then every {@code $PATH}
 * entry: a home with no launcher passed the filter whenever some OTHER GraalVM was on the path, and
 * the build then linked with that one while believing it had honoured the spec or the lock's pin.
 * A pin is a floor, so satisfying it with an unrelated toolchain is the one answer worse than none.
 */
public final class GraalHomeLookup {

    private GraalHomeLookup() {}

    /**
     * The installed home for {@code projectDir}, or empty when only an install or a prompt could
     * answer. {@code specs} are the explicit-spec candidates in precedence order; the first
     * non-blank one wins and decides the outcome by itself.
     */
    public static Optional<Path> installed(Path projectDir, @Nullable Path jdksDir, @Nullable String... specs) {
        JdkRegistry registry = jdksDir != null ? new JdkRegistry(jdksDir) : new JdkRegistry();
        String effective = firstNonBlank(specs);
        if (effective != null) return bySpec(registry, effective);
        GraalPin pin = ToolchainPins.scan(projectDir).graal();
        if (pin != null) return byLockPin(registry, pin);
        Optional<Path> pointer = byInventory(registry);
        if (pointer.isPresent()) return pointer;
        return byPolicy(registry);
    }

    /** The install matching {@code spec} when it carries native-image; empty otherwise. */
    public static Optional<Path> bySpec(JdkRegistry registry, String spec) {
        return registry.findBySpec(spec).map(InstalledJdk::home).filter(GraalHomeLookup::usable);
    }

    /** The best installed Graal satisfying the lock's pin, when it carries native-image. */
    public static Optional<Path> byLockPin(JdkRegistry registry, GraalPin pin) {
        return LockPinMatch.chooseGraal(registry.listHits(), pin)
                .map(JdkHit::home)
                .filter(GraalHomeLookup::usable);
    }

    /** The {@code jk jdk graal} pointer's home, when set, still installed, and carrying native-image. */
    public static Optional<Path> byInventory(JdkRegistry registry) {
        try {
            JdkInventory inventory = JdkInventory.of(registry.jdksRoot());
            Optional<Path> home = inventory.graalHome();
            if (home.isPresent() && usable(home.get())) return home;
            Optional<String> id = inventory.graalId();
            if (id.isPresent()) {
                Optional<InstalledJdk> byId = registry.find(id.get());
                if (byId.isPresent() && usable(byId.get().home()))
                    return Optional.of(byId.get().home());
            }
        } catch (IOException ignored) {
            // no usable default-graal — the caller falls through to policy / ambient search
        }
        return Optional.empty();
    }

    /** The de-facto preferred installed Graal (the shell hook's policy), when it carries native-image. */
    public static Optional<Path> byPolicy(JdkRegistry registry) {
        return DefaultGraalPolicy.choose(registry.listHits()).map(JdkHit::home).filter(GraalHomeLookup::usable);
    }

    /** Does THIS home carry the launcher — not "is a launcher reachable from here somehow". */
    private static boolean usable(Path home) {
        return GraalLauncher.in(home).isPresent();
    }

    private static @Nullable String firstNonBlank(@Nullable String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
