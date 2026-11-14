// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

/** Whether this client may reach for a release directory ({@link cc.jumpkick.repo.ReleaseArtifacts}). */
public final class ReleasedClient {

    private ReleasedClient() {}

    /**
     * Fetch only for a released client — the native image, or the JVM client an installer laid
     * out ({@link JvmClient}) — online, and never for a {@code -SNAPSHOT}. A JVM started from a
     * checkout or a test is not a release and does not reach for one.
     */
    public static boolean applicable(String version, boolean releasedClient, boolean offline) {
        return releasedClient && !offline && !version.endsWith("-SNAPSHOT");
    }

    /** Whether this process is a released client: the native image or the installed JVM client. */
    public static boolean current() {
        return EngineSpawn.isNativeImage() || JvmClient.installed();
    }
}
