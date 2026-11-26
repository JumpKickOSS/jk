// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.forge;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * A GitLab project as the git remote {@code origin} names it, on gitlab.com or a self-managed host:
 * {@code web} is the instance's root ({@code https://gitlab.com}) and {@code path} the project's
 * full path, subgroups included ({@code acme/tools/app}).
 */
public record GitLabProject(URI web, String path) implements ForgeRepository {

    /** The REST API root: {@code <web>/api/v4}. */
    public URI api() {
        return URI.create(web + "/api/v4");
    }

    /** {@code {api}/projects/{path}}, the path URL-encoded as the project id. */
    public URI project(URI api) {
        String base = api.toString();
        return URI.create((base.endsWith("/") ? base : base + "/") + "projects/"
                + URLEncoder.encode(path, StandardCharsets.UTF_8));
    }

    /** {@code {api}/projects/{path}/packages/generic/{name}/{version}/}: a generic package's files. */
    public String packages(URI api, String name, String version) {
        return project(api) + "/packages/generic/" + name + "/" + version + "/";
    }

    @Override
    public String host() {
        return String.valueOf(web.getRawAuthority());
    }

    @Override
    public ForgeKind kind() {
        return ForgeKind.GITLAB;
    }

    @Override
    public String forge() {
        return "GitLab";
    }

    @Override
    public String login() {
        return "run jk auth login gitlab or set GITLAB_TOKEN";
    }

    @Override
    public String toString() {
        return path;
    }

    /**
     * {@code remoteUrl} (https, ssh or scp form) as a GitLab project.
     *
     * @throws IllegalArgumentException with one line when it names none
     */
    public static GitLabProject parse(String remoteUrl, @Nullable Path sshConfig) {
        OriginUrl origin = OriginUrl.parse(remoteUrl, sshConfig, ForgeKind.GITLAB, "GitLab");
        return new GitLabProject(origin.web(), String.join("/", origin.segments()));
    }
}
