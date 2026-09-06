// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.tool;

import cc.jumpkick.jdk.DefaultGraalPolicy;
import cc.jumpkick.jdk.InstalledJdk;
import cc.jumpkick.jdk.JdkHit;
import cc.jumpkick.jdk.JdkInventory;
import cc.jumpkick.jdk.JdkRegistry;
import cc.jumpkick.jdk.LockPinMatch;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.ToolchainPins;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The installed GraalVM a native build should link with, found without installing or asking.
 *
 * <p>One policy for both callers. The CLI's {@code GraalResolver} runs these tiers before it
 * offers to install what is missing; the engine runs them for a request that shipped no client
 * answer — a build submitted over HTTP or MCP — which used to fall straight through to the
 * daemon's own {@code $GRAALVM_HOME} and whichever JDK happened to be pinned, so a workspace with
 * an always-native member linked against a Graal the request never named, or failed at its native
 * tail minutes in. The tiers, in order:
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
 * <p>Every answer is checked for a {@code native-image} launcher through {@link
 * NativeImageDriver#resolve(Path, Function)} against the caller's environment, never this
 * process's: inside the engine {@link System#getenv} is the daemon's.
 */
public final class GraalHomeLookup {

    private GraalHomeLookup() {}

    /**
     * The installed home for {@code projectDir}, or empty when only an install or a prompt could
     * answer. {@code specs} are the explicit-spec candidates in precedence order; the first
     * non-blank one wins and decides the outcome by itself.
     */
    public static Optional<Path> installed(
            Path projectDir,
            @Nullable Path jdksDir,
            Function<String, @Nullable String> env,
            @Nullable String... specs) {
        JdkRegistry registry = jdksDir != null ? new JdkRegistry(jdksDir) : new JdkRegistry();
        String effective = firstNonBlank(specs);
        if (effective != null) return bySpec(registry, effective, env);
        Lockfile.GraalPin pin = ToolchainPins.scan(projectDir).graal();
        if (pin != null) return byLockPin(registry, pin, env);
        Optional<Path> pointer = byInventory(registry, env);
        if (pointer.isPresent()) return pointer;
        return byPolicy(registry, env);
    }

    /** The install matching {@code spec} when it carries native-image; empty otherwise. */
    public static Optional<Path> bySpec(JdkRegistry registry, String spec, Function<String, @Nullable String> env) {
        return registry.findBySpec(spec).map(InstalledJdk::home).filter(home -> usable(home, env));
    }

    /** The best installed Graal satisfying the lock's pin, when it carries native-image. */
    public static Optional<Path> byLockPin(
            JdkRegistry registry, Lockfile.GraalPin pin, Function<String, @Nullable String> env) {
        return LockPinMatch.chooseGraal(registry.listHits(), pin)
                .map(JdkHit::home)
                .filter(home -> usable(home, env));
    }

    /** The {@code jk jdk graal} pointer's home, when set, still installed, and carrying native-image. */
    public static Optional<Path> byInventory(JdkRegistry registry, Function<String, @Nullable String> env) {
        try {
            JdkInventory inventory = JdkInventory.of(registry.jdksRoot());
            Optional<Path> home = inventory.graalHome();
            if (home.isPresent() && usable(home.get(), env)) return home;
            Optional<String> id = inventory.graalId();
            if (id.isPresent()) {
                Optional<InstalledJdk> byId = registry.find(id.get());
                if (byId.isPresent() && usable(byId.get().home(), env))
                    return Optional.of(byId.get().home());
            }
        } catch (IOException ignored) {
            // no usable default-graal — the caller falls through to policy / ambient search
        }
        return Optional.empty();
    }

    /** The de-facto preferred installed Graal (the shell hook's policy), when it carries native-image. */
    public static Optional<Path> byPolicy(JdkRegistry registry, Function<String, @Nullable String> env) {
        return DefaultGraalPolicy.choose(registry.listHits()).map(JdkHit::home).filter(home -> usable(home, env));
    }

    private static boolean usable(Path home, Function<String, @Nullable String> env) {
        return NativeImageDriver.resolve(home, env).isPresent();
    }

    private static @Nullable String firstNonBlank(@Nullable String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
