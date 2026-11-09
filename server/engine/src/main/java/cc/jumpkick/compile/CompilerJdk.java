// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Which JDK runs javac for a module. A language level ({@code java = N}) never chooses one: any
 * newer javac emits it with {@code --release N}, so a level jk's own runtime can emit compiles on
 * that runtime, in a worker that is already warm, and only a level above it moves to the project's
 * JDK. A JDK pin ({@code jdk = 21}, {@code jdk-vendor} / {@code jdk-version}, or {@code --jdk}) is
 * the author naming the compiler: the module compiles on that JDK even when the runtime could have
 * emitted its level.
 */
public final class CompilerJdk {

    /**
     * The oldest JDK the compiler worker runs on: jk's own floor. On a JDK below 18 javac does not
     * say which sources a generated file came from, so there a module that runs annotation
     * processors compiles whole.
     */
    public static final int WORKER_FLOOR = 17;

    private CompilerJdk() {}

    /** Whether {@code project}'s compiles are pinned to its own JDK: a manifest JDK spec, or {@code --jdk}. */
    public static boolean pinned(JkBuild project, @Nullable Session session) {
        return !project.project().jdkSpec().isEmpty() || (session != null && session.jdkSpec() != null);
    }

    /** {@link #home(Path, int, boolean, Path)} on jk's own runtime. */
    public static Path home(@Nullable Path project, int release, boolean pinned) {
        return home(project, release, pinned, JavaHomes.runningJavaHome());
    }

    /**
     * The JDK that compiles a module whose resolved JDK is {@code project} at {@code release}.
     * {@code host} is jk's own runtime. A pinned JDK the worker cannot run on (below {@link
     * #WORKER_FLOOR}), or one too old to emit the level, compiles on the runtime instead.
     */
    static Path home(@Nullable Path project, int release, boolean pinned, Path host) {
        if (project == null) return host;
        int projectFeature = JvmOptions.hostFeature(project);
        boolean canEmit = release <= 0 || projectFeature >= release;
        if (pinned && canEmit && projectFeature >= WORKER_FLOOR) return project;
        if (release <= 0 || release <= JvmOptions.hostFeature(host)) return host;
        return canEmit ? project : host;
    }
}
