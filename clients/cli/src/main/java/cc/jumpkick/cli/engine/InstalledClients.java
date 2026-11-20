// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cache.EngineInstall.ClientKind;
import cc.jumpkick.cache.EngineInstall.InstalledClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The PATH client an install records beside its engine ({@link EngineInstall#materialize(String,
 * Cas, String, boolean, InstalledClient)}), its bytes put in the store so {@code jk self rollback}
 * can put it back. Every path that installs an engine without a client of its own to name (the
 * installers' {@code jk self materialize}, the engine a client fetches on its first start) records
 * the client doing the install.
 */
public final class InstalledClients {

    private InstalledClients() {}

    /**
     * The client this process is, its bytes in {@code cas}: the JVM client's jar when it was launched
     * as one, else this native image's binary, else {@code fallback} (a native binary named by the
     * caller), else empty (a JVM that is no installed client, a test).
     */
    public static Optional<InstalledClient> running(Cas cas, @Nullable Path fallback) throws IOException {
        Optional<Path> jar = JvmClient.jar();
        if (jar.isPresent() && Files.isRegularFile(jar.get()))
            return Optional.of(ingest(cas, jar.get(), ClientKind.JVM));
        if (EngineSpawn.isNativeImage()) {
            Optional<String> exe = ProcessHandle.current().info().command();
            if (exe.isPresent() && Files.isRegularFile(Path.of(exe.get()))) {
                return Optional.of(ingest(cas, Path.of(exe.get()), ClientKind.NATIVE));
            }
        }
        if (fallback != null && Files.isRegularFile(fallback))
            return Optional.of(ingest(cas, fallback, ClientKind.NATIVE));
        return Optional.empty();
    }

    /** A client's bytes and kind. */
    public record Self(Path bytes, ClientKind kind) {}

    /**
     * This process when it is the installed PATH client: the JVM client its launcher names, or this
     * native image when it is the binary {@link EngineInstall#pathClient} names under {@code binDir}.
     * A client run from anywhere else (a build's dist, a test) is no installed client.
     */
    public static Optional<Self> installedSelf(Path binDir) {
        Optional<Path> jar = JvmClient.jar();
        if (jar.isPresent()) {
            return Files.isRegularFile(jar.get()) ? Optional.of(new Self(jar.get(), ClientKind.JVM)) : Optional.empty();
        }
        if (!EngineSpawn.isNativeImage()) return Optional.empty();
        Optional<String> exe = ProcessHandle.current().info().command();
        if (exe.isEmpty()) return Optional.empty();
        Path self = Path.of(exe.get());
        Path onPath = EngineInstall.pathClient(binDir);
        try {
            return Files.isRegularFile(onPath) && Files.isSameFile(self, onPath)
                    ? Optional.of(new Self(self, ClientKind.NATIVE))
                    : Optional.empty();
        } catch (IOException unreadable) {
            return Optional.empty();
        }
    }

    private static InstalledClient ingest(Cas cas, Path client, ClientKind kind) throws IOException {
        return new InstalledClient(cas.putFile(client).sha256(), kind, null);
    }
}
