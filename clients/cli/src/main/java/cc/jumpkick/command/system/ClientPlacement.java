// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cache.PathClients;
import cc.jumpkick.host.Os;
import cc.jumpkick.jdk.JdkFingerprint;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Puts a client the install pointer records back on the PATH under {@code binDir}, whichever kind
 * it is: a native binary renamed in (and any JVM client it replaces retired), or the JVM client's
 * jar placed under {@code <home>/lib/jk} with the launcher rewritten to run it. {@code jk self
 * update} and {@code jk self rollback} install clients through this one placer.
 */
public final class ClientPlacement {

    private ClientPlacement() {}

    /** The placer for the PATH under {@code binDir}. */
    public static EngineInstall.ClientPlacer at(Path binDir) {
        return (client, blob, version) -> switch (client.kind()) {
            case NATIVE -> {
                PathClients.installBinaries(blob, binDir);
                JvmClientInstall.retire(binDir, JvmClientInstall.libDir(), Os.isWindows());
                yield PathClients.pathClient(binDir);
            }
            case JVM -> {
                JvmClientInstall.install(
                        Files.readAllBytes(blob), JvmClientInstall.libDir(), binDir, version, java(), Os.isWindows());
                yield JvmClientInstall.launcher(binDir, Os.isWindows());
            }
        };
    }

    /**
     * The JDK a JVM client put back runs on: this JVM when this process runs on one, else {@code
     * JK_JAVA_HOME}, else {@code JAVA_HOME}; a native client has no JDK of its own to offer.
     */
    private static Path java() throws IOException {
        if (System.getProperty("org.graalvm.nativeimage.imagecode") == null) return JvmClientInstall.runningJava();
        for (String var : new String[] {"JK_JAVA_HOME", "JAVA_HOME"}) {
            String home = System.getenv(var);
            if (home == null || home.isBlank()) continue;
            Path java = JdkFingerprint.java(Path.of(home));
            if (Files.isRegularFile(java)) return java;
        }
        throw new IOException("putting back the JVM client needs a JDK 25 or newer: set JK_JAVA_HOME to one");
    }
}
