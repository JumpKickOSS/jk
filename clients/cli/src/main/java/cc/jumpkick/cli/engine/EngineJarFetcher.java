// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.repo.ReleaseVerifier;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Fetches the matching engine fat jar into {@code <home>/lib/jk-engine/} when spawn finds none
 * (built into {@link EngineClient}, no separate fetch command). Verifies {@code SHA256SUMS} and
 * materializes atomically so a torn download is never launchable.
 */
final class EngineJarFetcher {

    /** The release site root; one immutable subdirectory per version. */
    static final String DEFAULT_RELEASES_URL = "https://jumpkick.build/releases";

    private EngineJarFetcher() {}

    /** The release root, overridable via {@code JK_RELEASES_URL} (tests, mirrors, air-gapped hosts). */
    static URI releasesBase() {
        String override = System.getenv("JK_RELEASES_URL");
        return URI.create(override == null || override.isBlank() ? DEFAULT_RELEASES_URL : override);
    }

    /**
     * Fetch only for a released client — the native image, or the JVM client an installer laid
     * out ({@link cc.jumpkick.cli.engine.JvmClient}) — online, and never for a {@code -SNAPSHOT}. A JVM
     * started from a checkout or a test is not a release and does not reach for one.
     */
    static boolean applicable(String version, boolean releasedClient, boolean offline) {
        return releasedClient && !offline && !version.endsWith("-SNAPSHOT");
    }

    /**
     * Download, SHA-256-verify, and CAS-materialize the engine jar for {@code version}.
     * Unverified/partial jars are never left launchable.
     */
    static Path fetch(URI releasesBase, String version) throws IOException {
        return fetch(releasesBase, version, JkStores.storeCas(), EngineInstall.current());
    }

    /** Root-injected variant — the testable seam. */
    static Path fetch(URI releasesBase, String version, Cas cas, EngineInstall install) throws IOException {
        return fetch(releasesBase, version, cas, install, ReleaseVerifier.current(GlobalConfig.releaseTrustedKeys()));
    }

    /** Fully injected variant for tests. Release evidence is mandatory on every remote fetch. */
    static Path fetch(URI releasesBase, String version, Cas cas, EngineInstall install, ReleaseVerifier verifier)
            throws IOException {
        String jarName = "jk-engine-" + version + ".jar";
        URI versionDir = URI.create(releasesBase.toString() + "/" + version + "/");
        Http http = new Http();

        byte[] sumsBytes = get(http, versionDir.resolve("SHA256SUMS"), "release checksums");
        byte[] sig = get(http, versionDir.resolve("SHA256SUMS.sig"), "release signature");
        verifier.verify(sumsBytes, new String(sig, StandardCharsets.UTF_8));
        String expectedSha = shaFor(sumsBytes, jarName);
        byte[] jar = get(http, versionDir.resolve(jarName), "engine jar");
        String actualSha = Hashing.sha256Hex(jar);
        if (!actualSha.equalsIgnoreCase(expectedSha)) {
            throw new IOException("engine jar checksum mismatch for " + versionDir.resolve(jarName)
                    + " — expected sha256 " + expectedSha + ", got " + actualSha
                    + " (a mirror or proxy may have served a stale/corrupt file)");
        }

        cas.put(jar, actualSha);
        return install.materialize(version, cas, actualSha).engineJar();
    }

    private static byte[] get(Http http, URI uri, String what) throws IOException {
        HttpResponse<byte[]> response;
        try {
            response = http.get(uri);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while downloading the " + what + " from " + uri);
        } catch (IOException e) {
            throw new IOException(
                    "could not download the " + what + " from " + uri + " — " + e.getMessage()
                            + " (check your network, or materialize the version with `jk self update`)",
                    e);
        }
        if (response.statusCode() != 200) {
            throw new IOException(
                    "could not download the " + what + " from " + uri + " — HTTP " + response.statusCode());
        }
        return response.body();
    }

    /** Parse the strict signed checksum manifest for {@code jarName}. */
    private static String shaFor(byte[] sumsBody, String jarName) throws IOException {
        return ReleaseVerifier.sha256For(sumsBody, jarName);
    }
}
