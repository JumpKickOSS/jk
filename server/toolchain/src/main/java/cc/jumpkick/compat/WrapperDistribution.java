// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import cc.jumpkick.model.RepositorySpec;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;

/**
 * The {@code distributionUrl} a Maven or Gradle wrapper names, admitted only over a transport
 * nobody on the network path can rewrite, or from this machine's own disk. The archive it points
 * at is executed, so a plaintext {@code http://} URL is refused unless the host is this machine's
 * loopback interface — a local mirror or a test stub has no network path to sit on. A {@code
 * file:} URL is an offline mirror; the installer holds it to the same digest requirement as a
 * download (a pin, or the publisher's checksum file beside the archive).
 */
public final class WrapperDistribution {

    private WrapperDistribution() {}

    /**
     * {@code url} as a URI, or an {@link IOException} naming the wrapper file, the URL and what
     * would be accepted when the URL is plaintext over the network or uses a scheme the installer
     * cannot fetch from.
     */
    public static URI secureUrl(String url, Path wrapperFile) throws IOException {
        String trimmed = url.trim();
        URI uri = URI.create(trimmed);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        switch (scheme) {
            case "https", "file" -> {
                return uri;
            }
            case "http" -> {
                if (RepositorySpec.loopback(uri.getHost())) return uri;
                throw new IOException("distributionUrl in "
                        + wrapperFile
                        + " uses plain http ("
                        + uri
                        + "); change it to https:// or a loopback mirror");
            }
            default ->
                throw new IOException("distributionUrl in "
                        + wrapperFile
                        + " ("
                        + trimmed
                        + ") has an unsupported scheme; use https://, file:// or http:// on a loopback host");
        }
    }
}
