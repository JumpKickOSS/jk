// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.forge;

import java.net.URI;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * A GitHub repository as the git remote {@code origin} names it: on github.com, a GitHub
 * Enterprise Cloud {@code *.ghe.com} host, or a GitHub Enterprise Server host. A project that sets
 * {@code release = "github"} says its forge is GitHub, so any host that is not another forge's
 * public one is taken as an Enterprise Server.
 */
public record GitHubRepository(String host, String owner, String name) implements ForgeRepository {

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

    @Override
    public ForgeKind kind() {
        return ForgeKind.GITHUB;
    }

    @Override
    public String forge() {
        return "GitHub";
    }

    @Override
    public String login() {
        return "run jk auth login github or set GH_TOKEN";
    }

    /** {@code owner/name}. */
    @Override
    public String toString() {
        return owner + "/" + name;
    }

    /**
     * {@code remoteUrl} (https, ssh or scp form) as a GitHub repository.
     *
     * @throws IllegalArgumentException with one line when it names none
     */
    public static GitHubRepository parse(String remoteUrl, @Nullable Path sshConfig) {
        OriginUrl origin = OriginUrl.parse(remoteUrl, sshConfig, ForgeKind.GITHUB, "GitHub");
        if (origin.segments().size() != 2) {
            throw new IllegalArgumentException("origin " + remoteUrl + " is not a GitHub remote");
        }
        return new GitHubRepository(
                origin.hostname(), origin.segments().get(0), origin.segments().get(1));
    }
}
