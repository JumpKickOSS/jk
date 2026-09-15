// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.repo.ReleaseVerifier;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

    /** Bytes per read while the jar streams in; each read advances the progress once. */
    private static final int DOWNLOAD_BUFFER = 64 * 1024;

    /**
     * The jar download as the terminal shows it: opened with the jar's size, fed cumulative bytes,
     * then settled on the jar in place. The checksum and signature fetches before it are small and
     * unreported.
     */
    interface Progress {
        /** Renders nothing. */
        Progress NONE = new Progress() {};

        /** The jar download is about to begin; {@code totalBytes} is 0 when the server did not say. */
        default void start(String jarName, long totalBytes) {}

        /** Cumulative bytes read so far; {@code totalBytes} mirrors {@link #start}. */
        default void progress(long readBytes, long totalBytes) {}

        /** The verified jar is in place under {@code lib/jk-engine/}. */
        default void done(Path engineJar) {}
    }

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
    static Path fetch(URI releasesBase, String version, Progress progress) throws IOException {
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
                Progress.NONE);
    }

    /** Fully injected variant for tests. Release evidence is mandatory on every remote fetch. */
    static Path fetch(URI releasesBase, String version, Cas cas, EngineInstall install, ReleaseVerifier verifier)
            throws IOException {
        return fetch(releasesBase, version, cas, install, verifier, Progress.NONE);
    }

    /** As above, reporting the jar download to {@code progress}. */
    static Path fetch(
            URI releasesBase,
            String version,
            Cas cas,
            EngineInstall install,
            ReleaseVerifier verifier,
            Progress progress)
            throws IOException {
        String jarName = "jk-engine-" + version + ".jar";
        URI versionDir = URI.create(releasesBase.toString() + "/" + version + "/");
        Http http = new Http();

        byte[] sumsBytes = get(http, versionDir.resolve("SHA256SUMS"), "release checksums");
        byte[] sig = get(http, versionDir.resolve("SHA256SUMS.sig"), "release signature");
        verifier.verify(sumsBytes, new String(sig, StandardCharsets.UTF_8));
        String expectedSha = shaFor(sumsBytes, jarName);
        byte[] jar = stream(http, versionDir.resolve(jarName), jarName, progress);
        String actualSha = Hashing.sha256Hex(jar);
        if (!actualSha.equalsIgnoreCase(expectedSha)) {
            throw new IOException("engine jar checksum mismatch for " + versionDir.resolve(jarName)
                    + " — expected sha256 " + expectedSha + ", got " + actualSha
                    + " (a mirror or proxy may have served a stale/corrupt file)");
        }

        cas.put(jar, actualSha);
        Path engineJar = install.materialize(version, cas, actualSha).engineJar();
        progress.done(engineJar);
        return engineJar;
    }

    /**
     * The jar, streamed so {@code progress} advances as bytes arrive rather than once at the end.
     * The whole payload is still held in memory for the checksum: it is the engine's size, and the
     * CAS takes bytes.
     */
    private static byte[] stream(Http http, URI uri, String jarName, Progress progress) throws IOException {
        HttpResponse<InputStream> response;
        try {
            response = http.getStream(uri);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while downloading the engine jar from " + uri);
        } catch (IOException e) {
            throw downloadFailed(uri, "engine jar", e);
        }
        if (response.statusCode() != 200) {
            try (InputStream body = response.body()) {
                body.transferTo(OutputStream.nullOutputStream());
            }
            throw new IOException("could not download the engine jar from " + uri + " — HTTP " + response.statusCode());
        }
        long total = contentLength(response);
        progress.start(jarName, total);
        ByteArrayOutputStream jar =
                new ByteArrayOutputStream(total > 0 && total < Integer.MAX_VALUE ? (int) total : DOWNLOAD_BUFFER);
        try (InputStream body = response.body()) {
            byte[] buf = new byte[DOWNLOAD_BUFFER];
            long read = 0;
            int n;
            while ((n = body.read(buf)) > 0) {
                jar.write(buf, 0, n);
                read += n;
                progress.progress(read, total);
            }
        } catch (IOException e) {
            throw downloadFailed(uri, "engine jar", e);
        }
        return jar.toByteArray();
    }

    /**
     * The body size the server declared, for an identity-encoded body; 0 when it did not say or
     * the body is content-encoded, since the stream hands back decoded bytes the declared length
     * does not count.
     */
    static long contentLength(HttpResponse<?> response) {
        boolean encoded = response.headers()
                .firstValue("Content-Encoding")
                .map(v -> !v.equalsIgnoreCase("identity"))
                .orElse(false);
        if (encoded) return 0;
        return response.headers().firstValueAsLong("Content-Length").orElse(0L);
    }

    private static IOException downloadFailed(URI uri, String what, IOException cause) {
        return new IOException(
                "could not download the " + what + " from " + uri + " — " + cause.getMessage()
                        + " (check your network, or materialize the version with `jk self update`)",
                cause);
    }

    private static byte[] get(Http http, URI uri, String what) throws IOException {
        HttpResponse<byte[]> response;
        try {
            response = http.get(uri);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while downloading the " + what + " from " + uri);
        } catch (IOException e) {
            throw downloadFailed(uri, what, e);
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
