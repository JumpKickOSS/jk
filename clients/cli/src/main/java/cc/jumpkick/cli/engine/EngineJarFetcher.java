// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.GlobalConfig;
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

    /** Download, verify, and CAS-materialize the engine jar for {@code version}. */
    static Path fetch(URI releasesBase, String version, ReleaseArtifacts.Progress progress) throws IOException {
        return fetch(
                releasesBase,
                version,
                JkStores.storeCas(),
                EngineInstall.current(),
                ReleaseVerifier.current(GlobalConfig.releaseTrustedKeys()),
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
                ReleaseArtifacts.Progress.NONE);
    }

    /** Fully injected variant for tests. Release evidence is mandatory on every remote fetch. */
    static Path fetch(URI releasesBase, String version, Cas cas, EngineInstall install, ReleaseVerifier verifier)
            throws IOException {
        return fetch(releasesBase, version, cas, install, verifier, ReleaseArtifacts.Progress.NONE);
    }

    /** As above, reporting the jar download to {@code progress}. */
    static Path fetch(
            URI releasesBase,
            String version,
            Cas cas,
            EngineInstall install,
            ReleaseVerifier verifier,
            ReleaseArtifacts.Progress progress)
            throws IOException {
        ReleaseArtifacts.Verified jar = ReleaseArtifacts.fetch(
                releasesBase, version, "jk-engine-" + version + ".jar", "engine jar", verifier, progress);
        cas.put(jar.bytes(), jar.sha256());
        Path engineJar = install.materialize(version, cas, jar.sha256()).engineJar();
        progress.done(engineJar);
        return engineJar;
    }
}
