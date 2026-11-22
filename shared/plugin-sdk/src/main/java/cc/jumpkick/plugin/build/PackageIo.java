// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.ProjectFacts;
import cc.jumpkick.plugin.RepositoryRoute;
import cc.jumpkick.plugin.RuntimeEntry;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * What a {@link PackagerSpec.Body} gets: the resolved declared inputs (with coordinate-named
 * runtime entries), the destination artifact path, and progress labelling. The engine already
 * decided whether this run is needed (declared-input action key) and owns where the artifact
 * lives.
 */
public interface PackageIo {

    Path classesDir();

    /** The module's project dir — for packager inputs declared as {@link In#projectFiles}. */
    Path moduleDir();

    /** Lock-ordered production RUNTIME entries — {@link In#runtimeEntries()}. */
    List<RuntimeEntry> runtimeEntries();

    PluginConfig config();

    ProjectFacts project();

    /** A chained step's output root — {@link In#stepOutput}; empty when the step did not run. */
    Optional<Path> stepOutput(String step);

    /**
     * An engine-supplied extra: a manifest-contributed {@code packager-dependency} artifact (by
     * its artifact id), or a named file the engine prepares (e.g. {@code sbom}).
     */
    Optional<Path> extra(String name);

    /** Where the produced artifact must land (the module's main-artifact path). */
    Path artifactPath();

    /**
     * The remote repositories this module resolves against, in resolve order and as the engine
     * routes them ({@link RepositoryRoute}) — {@link In#repositories()}. A packager that fetches
     * outside the lock (a native tool, an OCI base) asks exactly these and never names Central
     * itself. Empty when the packager did not declare the input.
     */
    List<RepositoryRoute> repositories();

    /**
     * The resolved value of the plugin's secret key {@code key} (signing credentials: {@code
     * signing.<key>}), diverted off the config so it never reaches describe payloads, the config
     * token or logs; empty when the module sets none. NEVER echo a secret into labels or errors.
     */
    default Optional<String> secret(String key) {
        return Optional.empty();
    }

    /** The JDK this build runs on — for {@link #tool} forks (signing, keytool, …). */
    Path javaHome();

    /** A {@code bin/<name>} fork off {@link #javaHome()}. */
    default TaskExec.ToolRun tool(String bin) {
        return new TaskExec.ToolRun(javaHome(), bin);
    }

    /** A fork of an arbitrary executable (a fetched native tool). */
    default TaskExec.ToolRun tool(Path executable) {
        return new TaskExec.ToolRun(executable);
    }

    /** Convenience for the common case. */
    default TaskExec.ToolRun java() {
        return tool("java");
    }

    /**
     * Whether this job forbids network access — the user's {@code --offline}, decided once by the
     * engine and stamped onto the spec at the fork. A worker that is about to reach out asks this
     * first and refuses, naming what it wanted; it must never consult {@code JK_OFFLINE} or a
     * system property, which inside a forked JVM describe the engine daemon's startup environment
     * rather than this job.
     */
    boolean offline();

    /** Progress label surfaced in the build UI. */
    void label(String text);

    /** A body this packager downloads itself, reported to the run's download board; see {@link Transfers}. */
    default Transfers.Transfer transfer(String label) {
        return Transfers.open(label);
    }

    /**
     * Declare an extra produced file or directory (beyond {@link #artifactPath()}) that the
     * engine must cache and restore with the artifact — e.g. the quarkus fast-jar {@code lib/}
     * siblings. Must live under the artifact's directory. Directories are stored recursively, and a
     * top-level directory an earlier package declared and this one does not is removed, whether this
     * one is packaged or restored.
     */
    default void produced(Path path) {}
}
