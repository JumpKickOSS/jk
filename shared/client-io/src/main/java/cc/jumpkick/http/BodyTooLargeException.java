// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import java.io.IOException;
import java.net.URI;

/**
 * A response body larger than the most {@link Http} reads into memory for the request. Not
 * retried: the same body would come back.
 */
public final class BodyTooLargeException extends IOException {

    BodyTooLargeException(URI uri, long limit) {
        super("the response from " + SafeUri.forMessage(uri) + " is larger than " + mebibytes(limit)
                + "; stopped reading");
    }

    private static String mebibytes(long bytes) {
        return bytes % (1024 * 1024) == 0 ? bytes / (1024 * 1024) + " MiB" : bytes + " bytes";
    }
}
