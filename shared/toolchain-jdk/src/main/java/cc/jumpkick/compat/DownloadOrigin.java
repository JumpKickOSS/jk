// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import java.net.URI;

/**
 * Where a download comes from: a base URL, always ending in {@code /}, and the id its credential is
 * looked up under in the repository credential chain ({@code host[:port]}, or a settings.xml mirror's id).
 */
public record DownloadOrigin(URI url, String credentialId) {

    /** An origin addressed by its host: the credential id is {@code host[:port]}. */
    public static DownloadOrigin ofHost(URI url) {
        return new DownloadOrigin(url, hostId(url));
    }

    /** {@code host[:port]}: the id a host-addressed origin's credential is stored under. */
    public static String hostId(URI url) {
        return url.getHost() + (url.getPort() == -1 ? "" : ":" + url.getPort());
    }

    /** {@code url} with a trailing slash, so relative paths resolve beneath it. */
    public static URI directory(String url) {
        return URI.create(url.endsWith("/") ? url : url + "/");
    }
}
