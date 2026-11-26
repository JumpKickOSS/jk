// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.forge;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A Forgejo or Gitea repository as the git remote {@code origin} names it, on codeberg.org or any
 * self-hosted instance: {@code web} is the instance's root, with the sub-path an https remote shows
 * before {@code owner/name} ({@code https://git.acme.corp/forge}).
 */
public record ForgejoRepository(URI web, String owner, String name) implements ForgeRepository {

    /** The REST API root: {@code <web>/api/v1}. */
    public URI api() {
        return URI.create(web + "/api/v1");
    }

    /** {@code {api}/repos/{owner}/{name}}. */
    public URI repos(URI api) {
        String base = api.toString();
        return URI.create((base.endsWith("/") ? base : base + "/") + "repos/" + owner + "/" + name);
    }

    /** Where a published release's files download from: {@code <web>/<owner>/<name>/releases/download/<tag>/}. */
    public String download(String tag) {
        return web + "/" + owner + "/" + name + "/releases/download/" + tag + "/";
    }

    /** An attachment's bytes by its UUID, {@code <web>/attachments/<uuid>}, which a draft's attachments also answer. */
    public URI attachment(String uuid) {
        return URI.create(web + "/attachments/" + uuid);
    }

    @Override
    public String host() {
        return String.valueOf(web.getRawAuthority());
    }

    @Override
    public ForgeKind kind() {
        return ForgeKind.GITEA;
    }

    @Override
    public String forge() {
        return "Forgejo";
    }

    @Override
    public String login() {
        return "run jk auth login forgejo or set FORGEJO_TOKEN";
    }

    @Override
    public String toString() {
        return owner + "/" + name;
    }

    /**
     * {@code remoteUrl} (https, ssh or scp form) as a Forgejo or Gitea repository.
     *
     * @throws IllegalArgumentException with one line when it names none
     */
    public static ForgejoRepository parse(String remoteUrl, @Nullable Path sshConfig) {
        OriginUrl origin = OriginUrl.parse(remoteUrl, sshConfig, ForgeKind.GITEA, "Forgejo");
        List<String> s = origin.segments();
        String prefix = String.join("/", s.subList(0, s.size() - 2));
        URI web = prefix.isEmpty() ? origin.web() : URI.create(origin.web() + "/" + prefix);
        return new ForgejoRepository(web, s.get(s.size() - 2), s.get(s.size() - 1));
    }
}
