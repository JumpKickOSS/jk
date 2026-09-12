// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import cc.jumpkick.model.RepositorySpec;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

/**
 * The {@code distributionUrl} a Maven or Gradle wrapper names, admitted only over a transport
 * nobody on the network path can rewrite. The archive it points at is executed, so a plaintext
 * {@code http://} URL is refused unless the host is this machine's loopback interface — a local
 * mirror or a test stub has no network path to sit on.
 */
public final class WrapperDistribution {

    private WrapperDistribution() {}

    /**
     * {@code url} as a URI, or an {@link IOException} naming the wrapper file when the URL is
     * plaintext over the network.
     */
    public static URI secureUrl(String url, Path wrapperFile) throws IOException {
        URI uri = URI.create(url.trim());
        if ("http".equalsIgnoreCase(uri.getScheme()) && !RepositorySpec.loopback(uri.getHost())) {
            throw new IOException("distributionUrl in "
                    + wrapperFile
                    + " is plaintext http ("
                    + uri
                    + "): the archive it names is executed, so it must be fetched over https."
                    + " Change the URL to https:// or point it at a loopback mirror.");
        }
        return uri;
    }
}
