// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.forge;

import cc.jumpkick.util.GitUrl;
import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A GitHub repository as the git remote {@code origin} names it: on github.com, a GitHub
 * Enterprise Cloud {@code *.ghe.com} host, or a GitHub Enterprise Server host. A project that sets
 * {@code release = "github"} says its forge is GitHub, so any host that is not another forge's
 * public one is taken as an Enterprise Server.
 */
public record GitHubRepository(String host, String owner, String name) {

    /** The REST API root: {@code https://api.github.com}, {@code https://api.<host>} or {@code https://<host>/api/v3}. */
    public URI api() {
        if (host.equals("github.com")) return URI.create("https://api.github.com");
        if (host.endsWith(".ghe.com")) return URI.create("https://api." + host);
        return URI.create("https://" + host + "/api/v3");
    }

    /** The asset upload root: {@code https://uploads.github.com}, {@code https://uploads.<host>} or {@code https://<host>/api/uploads}. */
    public URI uploads() {
        if (host.equals("github.com")) return URI.create("https://uploads.github.com");
        if (host.endsWith(".ghe.com")) return URI.create("https://uploads." + host);
        return URI.create("https://" + host + "/api/uploads");
    }

    /** {@code {api}/repos/{owner}/{name}}. */
    public URI repos(URI api) {
        String base = api.toString();
        return URI.create((base.endsWith("/") ? base : base + "/") + "repos/" + owner + "/" + name);
    }

    /** Where a published release's file {@code <name>} downloads from: {@code https://<host>/<owner>/<name>/releases/download/<tag>/}. */
    public String download(String tag) {
        return "https://" + host + "/" + owner + "/" + name + "/releases/download/" + tag + "/";
    }

    /** {@code owner/name}. */
    @Override
    public String toString() {
        return owner + "/" + name;
    }

    /**
     * The repository {@code dir}'s checkout names as {@code origin}, an {@code ~/.ssh/config} host
     * alias resolved.
     *
     * @throws IllegalArgumentException with one line when origin is missing or not a GitHub remote
     */
    public static GitHubRepository fromOrigin(Path dir) {
        Path sshConfig = Optional.ofNullable(System.getProperty("user.home"))
                .map(h -> Path.of(h, ".ssh", "config"))
                .orElse(null);
        return fromOrigin(dir, sshConfig);
    }

    static GitHubRepository fromOrigin(Path dir, @Nullable Path sshConfig) {
        String url = GitForgeDetector.readOriginUrl(dir)
                .orElseThrow(
                        () -> new IllegalArgumentException("release = \"github\" needs a git remote origin on GitHub"));
        return parse(url, sshConfig);
    }

    /**
     * {@code remoteUrl} (https, ssh or scp form) as a GitHub repository.
     *
     * @throws IllegalArgumentException with one line when it names none
     */
    public static GitHubRepository parse(String remoteUrl, @Nullable Path sshConfig) {
        String refused = "origin " + remoteUrl + " is not a GitHub remote";
        String host = GitForgeDetector.hostOf(remoteUrl).orElseThrow(() -> new IllegalArgumentException(refused));
        String real = GitForgeDetector.resolveSshAlias(host, sshConfig).orElse(host);
        Optional<ForgeKind> known = ForgeKind.inferFromHost(real);
        if (known.isPresent() && known.get() != ForgeKind.GITHUB) throw new IllegalArgumentException(refused);
        String path;
        try {
            path = URI.create(GitUrl.canonicalize(remoteUrl)).getPath();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(refused);
        }
        String[] parts =
                path == null ? new String[0] : path.replaceAll("^/+", "").split("/");
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) throw new IllegalArgumentException(refused);
        String repo = parts[1].endsWith(".git") ? parts[1].substring(0, parts[1].length() - 4) : parts[1];
        return new GitHubRepository(real.toLowerCase(Locale.ROOT), parts[0], repo);
    }
}
