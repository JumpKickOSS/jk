// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A plugin capability: name the files a build of the module needs that only the plugin's own
 * resolution can name — Quarkus's extension deployment artifacts, which Quarkus finds through each
 * runtime extension's descriptor and its own conditional rules — so {@code jk lock} pins them.
 * Implemented alongside {@link cc.jumpkick.plugin.Plugin}; each closure is declared in {@code
 * jk-plugin.toml} as {@code [[contribute.lock-closure]] name = "<name>"}, with {@code for-step}
 * naming the steps that read it.
 *
 * <p>{@code jk lock} asks only for a closure an active plugin declares, and only when the module's
 * inputs to it — the plugin (jk's version for one jk bundles), the module, its locked rows and the
 * plugin's tool rows — differ from the ones the lock already answers for; every other lock and
 * every build forks nothing for it. The plugin's table is not an input. The lock pins each file
 * by digest, {@code jk sync} puts them in the store, and each step the closure names receives a
 * Maven-layout repository of exactly those files under {@code exec.extra("lock-closure.<name>")},
 * built from the store when the step runs (and again if the store's collector reclaimed it).
 */
@FunctionalInterface
public interface LockExtension {

    /**
     * One file of a closure: {@code coordinate} is {@code group:artifact:version[:classifier][!type]}
     * ({@code !pom} for a POM; no type for a jar); {@code file} is where the plugin's resolver holds
     * its bytes, when it does, so the lock takes them in rather than downloading them again.
     */
    record Pin(String coordinate, @Nullable Path file) {}

    /**
     * Every file the closure {@code name} needs: what the plugin's resolver read, POMs and parents
     * included. {@code exec} is a step's view of the module as the lock being written resolves it:
     * {@link TaskExec#runtimeEntries()} is the production closure, with the plugin's config, its
     * tools ({@link TaskExec#extra}), the routed repositories and {@link TaskExec#java()}; nothing is
     * compiled at lock time, so {@link TaskExec#classesDir()} is empty. {@code testRuntimeEntries}
     * is the test runtime closure. Under {@code exec.extra("lock-closure.<name>")} is a Maven-layout
     * repository of what jk already holds of the closure, to read before the network.
     */
    List<Pin> lockClosure(String name, TaskExec exec, List<PackageIo.RuntimeEntry> testRuntimeEntries) throws Exception;
}
