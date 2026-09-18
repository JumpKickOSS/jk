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
 * raw.githubusercontent.com — fetched once into the store under {@code tools/url/<sha256 of the
 * URL>/<file name>} and read from there on every build after, the way a tool closure is. The URL
 * is the file's identity: a cached copy is never fetched again, and its content is what the
 * step's action key hashes. An offline build with no copy fails naming the URL instead of reaching
 * out.
 */
final class UrlTools {

    private UrlTools() {}

    /** The cached file for {@code url}, fetched on first use. */
    static Path fetch(String url, Cas cas) throws IOException, InterruptedException {
        URI uri = URI.create(url);
        Path dir = cas.root()
                .resolve("tools")
                .resolve("url")
                .resolve(Hashing.sha256Hex(url.getBytes(StandardCharsets.UTF_8)).substring(0, 40));
        Path file = dir.resolve(fileName(uri));
        if (Files.isRegularFile(file)) return file;
        if (SessionContext.current().offline()) {
            throw new IOException("cannot fetch " + url + " under --offline and no copy is in the store yet; run once"
                    + " online to cache it");
        }
        HttpResponse<byte[]> response = new Http().get(uri);
        if (response.statusCode() != 200) {
            throw new IOException("fetching " + url + " returned " + response.statusCode());
        }
        Files.createDirectories(dir);
        AtomicWrites.replace(file, response.body());
        return file;
    }

    /** The URL's last path segment, so the tool sees the name the rule set is published under; {@code file} when it has none. */
    static String fileName(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.isBlank() ? "file" : name;
    }
}
