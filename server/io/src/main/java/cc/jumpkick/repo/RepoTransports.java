// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.http.Http;
import cc.jumpkick.model.ObjectStoreConfig;
import cc.jumpkick.repo.s3.AwsCredentialChain;
import cc.jumpkick.repo.s3.S3Transport;
import java.net.URI;
import java.util.Locale;

/**
 * Scheme → {@link RepoTransport}: http(s) → {@link HttpTransport}, s3/gs → {@link S3Transport},
 * file → {@link FileTransport}. Unsupported schemes fail loudly.
 */
public final class RepoTransports {

    private RepoTransports() {}

    /**
     * The transport for {@code url}'s scheme. {@code http}/{@code s3} both use the shared {@link
     * Http} client for the actual wire requests; an unsupported scheme is a clear error rather than a
     * silent fallthrough, so a mistyped or not-yet-supported URL fails loudly.
     */
    public static RepoTransport forUrl(URI url, Http http) {
        return forUrl(url, http, ObjectStoreConfig.EMPTY);
    }

    /**
     * As {@link #forUrl(URI, Http)} but with per-repo {@link ObjectStoreConfig} (region/endpoint/keys
     * from {@code jk.toml}) applied to object-store backends; ignored for {@code http(s)} and {@code
     * file://}.
     */
    public static RepoTransport forUrl(URI url, Http http, ObjectStoreConfig objectStore) {
        ObjectStoreConfig cfg = objectStore == null ? ObjectStoreConfig.EMPTY : objectStore;
        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
        return switch (scheme) {
            case "http", "https" -> new HttpTransport(http);
            case "s3" -> S3Transport.forS3(http, url, cfg, new AwsCredentialChain(), System::getenv);
            case "gs" -> S3Transport.forGcs(http, url, cfg, new AwsCredentialChain(), System::getenv);
            case "file" -> new FileTransport();
            default ->
                throw new IllegalArgumentException("no repository transport for scheme '"
                        + scheme
                        + "' in "
                        + url
                        + " (azblob arrives in a later slice)");
        };
    }
}
