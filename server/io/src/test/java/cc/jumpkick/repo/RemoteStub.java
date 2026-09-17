// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.credential.RepoCredential;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A remote repository as the transport sees it: a path-to-body map under a remote-looking host,
 * counting requests. The process-wide memos keep a remote's answers, so a test of what they hold
 * anchors on one of these rather than on a loopback stub or a {@code file://} directory, which
 * are asked afresh every time.
 */
final class RemoteStub implements RepoTransport {
    final Map<String, byte[]> served = new ConcurrentHashMap<>();
    private final Map<String, Integer> requests = new ConcurrentHashMap<>();
    private final URI base;

    RemoteStub(String host) {
        this.base = URI.create("https://" + host + "/");
    }

    MavenRepo repo(Path tmp, String name) {
        return MavenRepo.overTransport(
                name, base, this, new Cas(tmp.resolve("cas")), RepoCredential.ANONYMOUS, null, false, false, false);
    }

    int requestsFor(String path) {
        return requests.getOrDefault(path, 0);
    }

    @Override
    public Optional<byte[]> fetch(URI uri, RepoCredential credential) {
        requests.merge(uri.getPath(), 1, Integer::sum);
        return Optional.ofNullable(served.get(uri.getPath()));
    }

    @Override
    public int put(URI uri, byte[] body, String contentType, RepoCredential credential) {
        return 405;
    }
}
