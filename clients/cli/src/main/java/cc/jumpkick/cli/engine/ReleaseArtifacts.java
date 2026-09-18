// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.repo.ReleaseVerifier;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.version.Versions;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * The verified download path from a release directory ({@code releases/<version>/}): the signed
 * {@code SHA256SUMS} first ({@link Manifest}), then each artifact, whose bytes must hash to the
 * manifest's entry. The engine jar ({@link EngineJarFetcher}), the Maven spy jar and {@code jk
 * self update}'s engine and client are fetched through here and nowhere else, so all are held to
 * the same evidence; {@link #latestVersion} reads the signed latest-release pointer the same way.
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

    /**
     * One release's verified {@code SHA256SUMS}: fetched and signature-checked once, then every
     * artifact of that release is downloaded and hashed against it. The manifest is signed but not
     * bound to its directory, so callers name artifacts with the version in the name.
     */
    public static final class Manifest {
        private final Http http;
        private final URI versionDir;
        private final byte[] sums;

        private Manifest(Http http, URI versionDir, byte[] sums) {
            this.http = http;
            this.versionDir = versionDir;
            this.sums = sums;
        }

        /** The manifest text, one {@code <sha256>  <name>} line per artifact. */
        public String text() {
            return new String(sums, StandardCharsets.UTF_8);
        }

        /** Whether the release ships {@code artifactName}. */
        public boolean has(String artifactName) throws IOException {
            return ReleaseVerifier.find(sums, artifactName).isPresent();
        }

        /**
         * Download {@code artifactName} from this release and verify its bytes against the
         * manifest's exact entry. {@code what} names the artifact in messages ({@code engine jar}).
         * Nothing is written here; the caller places the verified bytes.
         */
        public Verified fetch(String artifactName, String what, Progress progress) throws IOException {
            String expectedSha = ReleaseVerifier.sha256For(sums, artifactName);
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
    }

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
     * The verified manifest of {@code releasesBase/version/}: {@code SHA256SUMS} and its signature
     * fetched, the signature checked against {@code verifier}. Every artifact of that release is
     * then fetched through {@link Manifest#fetch}.
     */
    public static Manifest manifest(URI releasesBase, String version, ReleaseVerifier verifier) throws IOException {
        URI versionDir = URI.create(releasesBase.toString() + "/" + version + "/");
        Http http = new Http();
        byte[] sumsBytes = get(http, versionDir.resolve("SHA256SUMS"), "release checksums");
        byte[] sig = get(http, versionDir.resolve("SHA256SUMS.sig"), "release signature");
        verifier.verify(sumsBytes, new String(sig, StandardCharsets.UTF_8));
        return new Manifest(http, versionDir, sumsBytes);
    }

    /**
     * Download one artifact of {@code releasesBase/version/} through its verified manifest. {@code
     * what} names the artifact in messages ({@code engine jar}).
     */
    public static Verified fetch(
            URI releasesBase,
            String version,
            String artifactName,
            String what,
            ReleaseVerifier verifier,
            Progress progress)
            throws IOException {
        return manifest(releasesBase, version, verifier).fetch(artifactName, what, progress);
    }

    /**
     * The version the signed {@code latest/LATEST} pointer under {@code releasesBase} names, once
     * its signature verifies and it is not older than {@code running}. The pointer is the one
     * mutable input of an update, so a bucket writer or a mirror that rolls it back to an older,
     * validly signed release gets a refusal rather than a downgrade; an explicit {@code jk self
     * update <version>} never reads the pointer and stays the deliberate way down.
     */
    public static String latestVersion(URI releasesBase, ReleaseVerifier verifier, String running) throws IOException {
        Http http = new Http();
        byte[] pointer = get(http, URI.create(releasesBase + "/latest/LATEST"), "latest-release pointer");
        byte[] signature =
                get(http, URI.create(releasesBase + "/latest/LATEST.sig"), "latest-release pointer signature");
        return latestVersion(verifier, pointer, signature, running);
    }

    /** {@link #latestVersion(URI, ReleaseVerifier, String)} over bytes already fetched. */
    static String latestVersion(ReleaseVerifier verifier, byte[] pointer, byte[] signature, String running)
            throws IOException {
        verifier.verify(pointer, new String(signature, StandardCharsets.UTF_8));
        String latest = ReleaseVerifier.parsePointer(pointer).version();
        if (Versions.compare(latest, running) < 0) {
            throw new IOException("the latest-release pointer names " + latest + ", older than the " + running
                    + " this jk runs — REFUSING a rolled-back pointer (a mirror or the release site may be"
                    + " stale or compromised; `jk self update " + latest + "` downgrades deliberately)");
        }
        return latest;
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
