// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.repo.ReleaseVerifier;
import cc.jumpkick.util.JkDirs;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * One verified download from a release directory ({@code releases/<version>/}): the signed
 * {@code SHA256SUMS} first, then the artifact, whose bytes must hash to the manifest's entry. The
 * engine jar ({@link EngineJarFetcher}) and the Maven spy jar are fetched through here and nowhere
 * else, so both are held to the same evidence.
 */
public final class ReleaseArtifacts {

    /** The release site root; one immutable subdirectory per version. */
    static final String DEFAULT_RELEASES_URL = "https://jumpkick.build/releases";

    /** Bytes per read while an artifact streams in; each read advances the progress once. */
    private static final int DOWNLOAD_BUFFER = 64 * 1024;

    /**
     * A download as the terminal shows it: opened with the artifact's size, fed cumulative bytes,
     * then settled on the file in place. The checksum and signature fetches before it are small
     * and unreported.
     */
    public interface Progress {
        /** Renders nothing. */
        Progress NONE = new Progress() {};

        /** The download is about to begin; {@code totalBytes} is 0 when the server did not say. */
        default void start(String artifactName, long totalBytes) {}

        /** Cumulative bytes read so far; {@code totalBytes} mirrors {@link #start}. */
        default void progress(long readBytes, long totalBytes) {}

        /** The verified artifact is in place. */
        default void done(Path artifact) {}
    }

    /** An artifact's bytes and the SHA-256 they were verified against. */
    public record Verified(byte[] bytes, String sha256) {}

    private ReleaseArtifacts() {}

    /**
     * The release root, overridable via {@code JK_RELEASES_URL} (tests, mirrors, air-gapped
     * hosts); read through {@link JkDirs#env} so a test can point one JVM at a stub directory.
     */
    public static URI releasesBase() {
        String override = JkDirs.env("JK_RELEASES_URL");
        return URI.create(override == null || override.isBlank() ? DEFAULT_RELEASES_URL : override);
    }

    /**
     * Fetch only for a released client — the native image, or the JVM client an installer laid
     * out ({@link JvmClient}) — online, and never for a {@code -SNAPSHOT}. A JVM started from a
     * checkout or a test is not a release and does not reach for one.
     */
    public static boolean applicable(String version, boolean releasedClient, boolean offline) {
        return releasedClient && !offline && !version.endsWith("-SNAPSHOT");
    }

    /** Whether this process is a released client: the native image or the installed JVM client. */
    public static boolean releasedClient() {
        return EngineSpawn.isNativeImage() || JvmClient.installed();
    }

    /**
     * Download {@code artifactName} from {@code releasesBase/version/} and verify it: the manifest
     * signature against {@code verifier}, then the bytes against the manifest's exact entry.
     * {@code what} names the artifact in messages ({@code engine jar}). Nothing is written here;
     * the caller places the verified bytes.
     */
    public static Verified fetch(
            URI releasesBase,
            String version,
            String artifactName,
            String what,
            ReleaseVerifier verifier,
            Progress progress)
            throws IOException {
        URI versionDir = URI.create(releasesBase.toString() + "/" + version + "/");
        Http http = new Http();

        byte[] sumsBytes = get(http, versionDir.resolve("SHA256SUMS"), "release checksums");
        byte[] sig = get(http, versionDir.resolve("SHA256SUMS.sig"), "release signature");
        verifier.verify(sumsBytes, new String(sig, StandardCharsets.UTF_8));
        String expectedSha = ReleaseVerifier.sha256For(sumsBytes, artifactName);
        URI uri = versionDir.resolve(artifactName);
        byte[] bytes = stream(http, uri, artifactName, what, progress);
        String actualSha = Hashing.sha256Hex(bytes);
        if (!actualSha.equalsIgnoreCase(expectedSha)) {
            throw new IOException(what + " checksum mismatch for " + uri
                    + " — expected sha256 " + expectedSha + ", got " + actualSha
                    + " (a mirror or proxy may have served a stale/corrupt file)");
        }
        return new Verified(bytes, actualSha);
    }

    /**
     * The artifact, streamed so {@code progress} advances as bytes arrive rather than once at the
     * end. The whole payload is still held in memory for the checksum: it is at most the engine's
     * size, and the CAS takes bytes.
     */
    private static byte[] stream(Http http, URI uri, String artifactName, String what, Progress progress)
            throws IOException {
        HttpResponse<InputStream> response;
        try {
            response = http.getStream(uri);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while downloading the " + what + " from " + uri);
        } catch (IOException e) {
            throw downloadFailed(uri, what, e);
        }
        if (response.statusCode() != 200) {
            try (InputStream body = response.body()) {
                body.transferTo(OutputStream.nullOutputStream());
            }
            throw new IOException(
                    "could not download the " + what + " from " + uri + " — HTTP " + response.statusCode());
        }
        long total = contentLength(response);
        progress.start(artifactName, total);
        ByteArrayOutputStream out =
                new ByteArrayOutputStream(total > 0 && total < Integer.MAX_VALUE ? (int) total : DOWNLOAD_BUFFER);
        try (InputStream body = response.body()) {
            byte[] buf = new byte[DOWNLOAD_BUFFER];
            long read = 0;
            int n;
            while ((n = body.read(buf)) > 0) {
                out.write(buf, 0, n);
                read += n;
                progress.progress(read, total);
            }
        } catch (IOException e) {
            throw downloadFailed(uri, what, e);
        }
        return out.toByteArray();
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
}
