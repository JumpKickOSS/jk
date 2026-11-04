// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import java.util.List;

/**
 * A plugin capability: name the files a build of the module needs that only the plugin's own
 * resolution can name — Quarkus's extension deployment artifacts, which Quarkus finds through each
 * runtime extension's descriptor and its own conditional rules — so {@code jk lock} pins them.
 * Implemented alongside {@link cc.jumpkick.plugin.Plugin}; each closure is declared in {@code
 * jk-plugin.toml} as {@code [[contribute.lock-closure]] name = "<name>"}.
 *
 * <p>{@code jk lock} asks only for a closure an active plugin declares, and only when the module's
 * inputs to it (the plugin and its version, the module's table, its locked rows) differ from the
 * ones the lock already answers for; every other lock and every build forks nothing for it. The
 * lock pins each file by digest, {@code jk sync} stages them into the store's Maven-layout mirror,
 * and a build or an offline build reads them there.
 */
@FunctionalInterface
public interface LockExtension {

    /**
     * Every file the closure {@code name} needs, each as {@code
     * group:artifact:version[:classifier][!type]} ({@code !pom} for a POM; no type for a jar).
     * {@code exec} is a step's view of the module as the lock being written resolves it: {@link
     * TaskExec#runtimeEntries()} is the production closure, with the plugin's config, its tools
     * ({@link TaskExec#extra}), the routed repositories and {@link TaskExec#java()}; nothing is
     * compiled at lock time, so {@link TaskExec#classesDir()} is empty. {@code testRuntimeEntries}
     * is the test runtime closure.
     */
    List<String> lockClosure(String name, TaskExec exec, List<PackageIo.RuntimeEntry> testRuntimeEntries)
            throws Exception;
}
