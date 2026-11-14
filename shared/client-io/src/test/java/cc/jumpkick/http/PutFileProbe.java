// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/** {@link HttpPutFileTest}'s child JVM: uploads {@code args[1]} to {@code args[0]} with {@link Http#putFile}. */
final class PutFileProbe {

    private PutFileProbe() {}

    public static void main(String[] args) throws Exception {
        var response = new Http(Http.standardClient(), new Duration[0])
                .putFile(URI.create(args[0]), Path.of(args[1]), Map.of());
        System.out.println("status " + response.statusCode());
    }
}
