// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.forge;

import cc.jumpkick.util.GitUrl;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A git remote URL (https, ssh or scp form) split into what a forge's API needs: the web root, the
 * host without a port, and the path's segments with {@code .git} dropped. The web root keeps an
 * http(s) remote's scheme and port; an ssh remote's web root is {@code https://<host>}.
 */
record OriginUrl(URI web, String hostname, List<String> segments) {

    /**
     * {@code remoteUrl} on a forge of {@code kind}, an {@code ~/.ssh/config} host alias resolved.
     *
     * @throws IllegalArgumentException with {@code origin <url> is not a <forge> remote} when it is
     *     local, has no path, or is on another forge's public host
     */
    static OriginUrl parse(String remoteUrl, @Nullable Path sshConfig, ForgeKind kind, String forge) {
        String refused = "origin " + remoteUrl + " is not a " + forge + " remote";
        String host = GitForgeDetector.hostOf(remoteUrl).orElseThrow(() -> new IllegalArgumentException(refused));
        String real =
                GitForgeDetector.resolveSshAlias(host, sshConfig).orElse(host).toLowerCase(Locale.ROOT);
        Optional<ForgeKind> known = ForgeKind.inferFromHost(real);
        if (known.isPresent() && known.get() != kind) throw new IllegalArgumentException(refused);
        URI uri;
        try {
            uri = URI.create(GitUrl.canonicalize(remoteUrl));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(refused);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean web = scheme.equals("http") || scheme.equals("https");
        String authority = web && uri.getPort() > 0 ? real + ":" + uri.getPort() : real;
        List<String> segments = new ArrayList<>();
        for (String s : (uri.getPath() == null ? "" : uri.getPath()).split("/")) {
            if (!s.isEmpty()) segments.add(s);
        }
        if (segments.size() < 2) throw new IllegalArgumentException(refused);
        return new OriginUrl(URI.create((web ? scheme : "https") + "://" + authority), real, List.copyOf(segments));
    }
}
