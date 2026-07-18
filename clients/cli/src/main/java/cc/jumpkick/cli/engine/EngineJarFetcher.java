// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.http.Http;
import cc.jumpkick.util.Hashing;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Fetches the matching engine fat jar into {@code ~/.jk/versions/<v>/} when spawn finds none
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

    /** Fetch only for native client, online, and non-{@code -SNAPSHOT} versions. */
    static boolean applicable(String version, boolean nativeImage, boolean offline) {
        return nativeImage && !offline && !version.endsWith("-SNAPSHOT");
    }

    /**
     * Download, SHA-256-verify, and CAS-materialize the engine jar for {@code version}.
     * Unverified/partial jars are never left launchable.
     */
    static Path fetch(URI releasesBase, String version) throws IOException {
        return fetch(
                releasesBase,
                version,
                new cc.jumpkick.cache.Cas(cc.jumpkick.util.JkDirs.cache()),
                cc.jumpkick.cache.VersionStore.current(),
                cc.jumpkick.task.CachePruneScheduler.resolveJkExe()
                        .map(Path::of)
                        .orElse(null));
    }

    /** Root-injected variant — the testable seam; production uses the live {@code ~/.jk} roots. */
    static Path fetch(
            URI releasesBase,
            String version,
            cc.jumpkick.cache.Cas cas,
            cc.jumpkick.cache.VersionStore store,
            Path clientBin)
            throws IOException {
        String jarName = "jk-engine-" + version + ".jar";
        URI versionDir = URI.create(releasesBase.toString() + "/" + version + "/");
        Http http = new Http();

        byte[] sumsBytes = get(http, versionDir.resolve("SHA256SUMS"), "release checksums");
        // Authenticity gate: when this host trusts any release key, the sums MUST carry a valid
        // signature (signature-then-hash) before any byte is used. Hosts with no keys proceed on
        // checksums alone.
        var verifier = cc.jumpkick.repo.ReleaseVerifier.current(cc.jumpkick.config.GlobalConfig.releaseTrustedKeys());
        if (verifier.available()) {
            byte[] sig = get(http, versionDir.resolve("SHA256SUMS.sig"), "release signature");
            verifier.verify(sumsBytes, new String(sig, java.nio.charset.StandardCharsets.UTF_8));
        }
        String expectedSha = shaFor(sumsBytes, jarName);
        byte[] jar = get(http, versionDir.resolve(jarName), "engine jar");
        String actualSha = Hashing.sha256Hex(jar);
        if (!actualSha.equalsIgnoreCase(expectedSha)) {
            throw new IOException("engine jar checksum mismatch for " + versionDir.resolve(jarName)
                    + " — expected sha256 " + expectedSha + ", got " + actualSha
                    + " (a mirror or proxy may have served a stale/corrupt file)");
        }

        // Verified bytes flow CAS-first into the side-by-side version layout. The running client
        // is this version's client — hand its own binary over for complete materialization.
        cas.put(jar, actualSha); // already hashed for verification — no second pass
        String clientSha = null;
        if (clientBin != null && Files.isRegularFile(clientBin)) {
            clientSha = Hashing.sha256Hex(clientBin);
            cas.putFile(clientBin, clientSha);
        }
        return store.materialize(version, cas, actualSha, clientSha).engineJar();
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

    /** Parse coreutils-style {@code SHA256SUMS} lines ({@code <hex>  <name>}) for {@code jarName}. */
    private static String shaFor(byte[] sumsBody, String jarName) throws IOException {
        String sums = new String(sumsBody, java.nio.charset.StandardCharsets.UTF_8);
        for (String line : sums.split("\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 2 && parts[1].equals(jarName)) return parts[0];
        }
        throw new IOException("release SHA256SUMS carries no entry for " + jarName
                + " — refusing to install an unverifiable engine jar");
    }
}
