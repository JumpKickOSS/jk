// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A step-dependency that is a file at an http(s) URL — a Checkstyle rule set a POM names on
 * raw.githubusercontent.com — kept in the store under {@code tools/url/<sha256 of the
 * URL>/<file name>}. {@code jk lock} downloads it and pins its sha256 in a {@code [[tool]]} row;
 * a build reads the stored copy when it is the pinned bytes and downloads it again otherwise,
 * refusing any other bytes. An offline build with no pinned copy fails naming the URL instead of
 * reaching out.
 */
final class UrlTools {

    private UrlTools() {}

    /** The stored file for {@code url}, verified as {@code sha256Hex}: fetched when no copy is those bytes. */
    static Path fetch(String url, Cas cas, String sha256Hex) throws IOException, InterruptedException {
        Path file = file(url, cas);
        if (Files.isRegularFile(file) && Hashing.sha256Hex(file).equalsIgnoreCase(sha256Hex)) return file;
        if (SessionContext.current().offline()) {
            throw new IOException("cannot fetch " + url + " under --offline and the store holds no copy of the"
                    + " bytes jk-lock.toml pins; run once online to cache it");
        }
        byte[] body = get(url);
        String served = Hashing.sha256Hex(body);
        if (!served.equalsIgnoreCase(sha256Hex)) {
            throw new IOException("jk-lock.toml pins sha256 " + sha256Hex + " for " + url + " but the server serves "
                    + served + "; if the file changed upstream, re-pin it with `jk lock -F`");
        }
        store(file, body);
        return file;
    }

    /**
     * {@code url}'s current bytes in the store, replacing any copy: what {@code jk lock} pins.
     * Offline, the stored copy stands in.
     */
    static Path download(String url, Cas cas) throws IOException, InterruptedException {
        Path file = file(url, cas);
        if (SessionContext.current().offline()) {
            if (Files.isRegularFile(file)) return file;
            throw new IOException("cannot fetch " + url + " under --offline and no copy is in the store yet; run once"
                    + " online to cache it");
        }
        store(file, get(url));
        return file;
    }

    private static Path file(String url, Cas cas) {
        return cas.root()
                .resolve("tools")
                .resolve("url")
                .resolve(Hashing.sha256Hex(url.getBytes(StandardCharsets.UTF_8)).substring(0, 40))
                .resolve(fileName(URI.create(url)));
    }

    private static byte[] get(String url) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = new Http().get(URI.create(url));
        if (response.statusCode() != 200) {
            throw new IOException("fetching " + url + " returned " + response.statusCode());
        }
        return response.body();
    }

    private static void store(Path file, byte[] body) throws IOException {
        Files.createDirectories(file.getParent());
        AtomicWrites.replace(file, body);
    }

    /** The URL's last path segment, so the tool sees the name the rule set is published under; {@code file} when it has none. */
    static String fileName(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.isBlank() ? "file" : name;
    }
}
