// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import java.io.IOException;
import java.net.URI;

/** A request a server answered with a status the caller does not accept: neither a success nor a 404. */
public final class HttpStatusException extends IOException {

    private final int status;

    public HttpStatusException(int status, URI uri) {
        super("HTTP " + status + " fetching " + SafeUri.forMessage(uri));
        this.status = status;
    }

    public int status() {
        return status;
    }

    /** True for 401 and 403: the server wants credentials, or refuses the ones it was given. */
    public boolean refusesAccess() {
        return status == 401 || status == 403;
    }
}
