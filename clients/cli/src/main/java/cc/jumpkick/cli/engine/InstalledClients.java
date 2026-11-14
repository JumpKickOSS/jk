// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cache.EngineInstall.ClientKind;
import cc.jumpkick.cache.EngineInstall.InstalledClient;
import cc.jumpkick.host.Hashing;
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
        if (jar.isPresent() && Files.isRegularFile(jar.get())) return Optional.of(ingest(cas, jar.get(), ClientKind.JVM));
        if (EngineSpawn.isNativeImage()) {
            Optional<String> exe = ProcessHandle.current().info().command();
            if (exe.isPresent() && Files.isRegularFile(Path.of(exe.get()))) {
                return Optional.of(ingest(cas, Path.of(exe.get()), ClientKind.NATIVE));
            }
        }
        if (fallback != null && Files.isRegularFile(fallback)) return Optional.of(ingest(cas, fallback, ClientKind.NATIVE));
        return Optional.empty();
    }

    private static InstalledClient ingest(Cas cas, Path client, ClientKind kind) throws IOException {
        String sha = Hashing.sha256Hex(client);
        cas.putFile(client, sha);
        return new InstalledClient(sha, kind, null);
    }
}
