// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import java.io.IOException;
import java.net.URI;

/**
 * A request refused because its host is on the {@link Http#DENY_HOSTS_ENV} list. Raised before any
 * bytes leave the process and never retried: the same list would refuse the same host again.
 */
public final class DeniedHostException extends IOException {

    private final String host;

    public DeniedHostException(URI uri, String host) {
        super("refusing outbound request to " + SafeUri.forMessage(uri) + ": " + host + " is on the "
                + Http.DENY_HOSTS_ENV + " deny list");
        this.host = host;
    }

    /** The host the list names, as the request spelled it. */
    public String host() {
        return host;
    }
}
