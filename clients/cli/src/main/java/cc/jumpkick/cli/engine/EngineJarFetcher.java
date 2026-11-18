// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.repo.ReleaseArtifacts;
import cc.jumpkick.repo.ReleaseVerifier;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

/**
 * Fetches the matching engine fat jar into {@code <home>/lib/jk-engine/} when spawn finds none
 * (built into {@link EngineClient}, no separate fetch command). The download and its evidence are
 * {@link ReleaseArtifacts}'; this materializes the verified bytes through the CAS, atomically, so a
 * torn download is never launchable.
 */
final class EngineJarFetcher {

    private EngineJarFetcher() {}

    /**
     * Download, verify, and CAS-materialize the engine jar for {@code version}. {@code downgrade}
     * ({@code -F}) lets it replace a newer live engine, which this client could not run anyway.
     */
    static Path fetch(URI releasesBase, String version, boolean downgrade, ReleaseArtifacts.Progress progress)
            throws IOException {
        return fetch(
                releasesBase,
                version,
                JkStores.storeCas(),
                EngineInstall.current(),
                ReleaseVerifier.current(GlobalConfig.releaseTrustedKeys()),
                downgrade,
                progress);
    }

    /** Root-injected variant — the testable seam. */
    static Path fetch(URI releasesBase, String version, Cas cas, EngineInstall install) throws IOException {
        return fetch(
                releasesBase,
                version,
                cas,
                install,
                ReleaseVerifier.current(GlobalConfig.releaseTrustedKeys()),
                false,
                ReleaseArtifacts.Progress.NONE);
    }

    /** Fully injected variant for tests. Release evidence is mandatory on every remote fetch. */
    static Path fetch(URI releasesBase, String version, Cas cas, EngineInstall install, ReleaseVerifier verifier)
            throws IOException {
        return fetch(releasesBase, version, cas, install, verifier, false, ReleaseArtifacts.Progress.NONE);
    }

    /**
     * As above, reporting the jar download to {@code progress}. The version is the running
     * client's, so a release site that does not publish it means this jk was built from a checkout.
     * A newer live engine is refused before the download unless {@code downgrade}.
     */
    static Path fetch(
            URI releasesBase,
            String version,
            Cas cas,
            EngineInstall install,
            ReleaseVerifier verifier,
            boolean downgrade,
            ReleaseArtifacts.Progress progress)
            throws IOException {
        try {
            install.requireAccepts(version, downgrade);
        } catch (IOException refused) {
            throw new IOException(
                    refused.getMessage() + "; run again with -F to put jk-engine " + version
                            + " in its place, or `jk install -S` in the checkout of the newer one",
                    refused);
        }
        ReleaseArtifacts.Verified jar;
        try {
            jar = ReleaseArtifacts.fetch(
                    releasesBase, version, "jk-engine-" + version + ".jar", "engine jar", verifier, progress);
        } catch (ReleaseArtifacts.NotPublished e) {
            throw unpublished(e);
        }
        cas.put(jar.bytes(), jar.sha256());
        // The client fetching its engine is the one paired with it: a home the native installer set
        // up gets its engine here, and records its client for a rollback here too.
        Path engineJar = install.materialize(
                        version,
                        cas,
                        jar.sha256(),
                        downgrade,
                        InstalledClients.running(cas, null).orElse(null))
                .engineJar();
        progress.done(engineJar);
        return engineJar;
    }

    private static IOException unpublished(ReleaseArtifacts.NotPublished e) {
        return new IOException(
                "jk " + e.version() + " is not a published release; run `jk install -S` in its checkout,"
                        + " or point JK_HOME at the home it is installed in",
                e);
    }
}
