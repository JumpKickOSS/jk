// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.http.Http;
import cc.jumpkick.repo.ReleaseArtifacts;
import cc.jumpkick.repo.ReleaseVerifier;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.util.zip.GZIPInputStream;

/**
 * The installer and the release, from the release site JumpKick itself updates from: {@code
 * install.sh} beside {@code releases/} (as {@code curl … | bash} fetches it), and each artifact
 * through {@link ReleaseArtifacts}, checked against the release's signed {@code SHA256SUMS}.
 */
public final class WslDownloads implements WslSetup.Downloads {

    private final URI releases;
    private final ReleaseVerifier verifier;

    public WslDownloads(URI releases, ReleaseVerifier verifier) {
        String base = releases.toString();
        this.releases = URI.create(base.endsWith("/") ? base.substring(0, base.length() - 1) : base);
        this.verifier = verifier;
    }

    /** The release site JumpKick updates from, with the keys it trusts. */
    public static WslDownloads live() {
        return new WslDownloads(
                ReleaseArtifacts.releasesBase(), ReleaseVerifier.current(GlobalConfig.releaseTrustedKeys()));
    }

    /** Whether the release site serves {@code version}: its signed sums are there. */
    public boolean published(String version) throws IOException {
        try {
            ReleaseArtifacts.manifest(releases, version, verifier);
            return true;
        } catch (ReleaseArtifacts.NotPublished absent) {
            return false;
        }
    }

    /** The version the signed latest pointer names. */
    public String latest() throws IOException {
        return ReleaseArtifacts.latestVersion(releases, verifier, "0.0.0");
    }

    @Override
    public byte[] installer() throws IOException {
        URI uri = releases.resolve("install.sh");
        HttpResponse<byte[]> r;
        try {
            r = new Http().get(uri);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while downloading " + uri);
        }
        if (r.statusCode() != 200) throw new IOException("could not download " + uri + ": HTTP " + r.statusCode());
        return r.body();
    }

    @Override
    public byte[] client(String version, String arch) throws IOException {
        String name = "jk-linux-" + arch + "-" + version + ".gz";
        byte[] gz = ReleaseArtifacts.fetch(
                        releases, version, name, "Linux client", verifier, ReleaseArtifacts.Progress.NONE)
                .bytes();
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            return in.readAllBytes();
        }
    }

    @Override
    public byte[] engine(String version) throws IOException {
        String name = "jk-engine-" + version + ".jar";
        return ReleaseArtifacts.fetch(releases, version, name, "engine jar", verifier, ReleaseArtifacts.Progress.NONE)
                .bytes();
    }
}
