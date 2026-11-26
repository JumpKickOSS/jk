// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.forge.ForgejoRepository;
import cc.jumpkick.http.Http;
import cc.jumpkick.jsonl.MiniJson;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A Forgejo or Gitea release of the tag (Codeberg's included), a {@link ReleaseDraft} until the
 * final run publishes it. Attachments upload as the raw body of {@code POST
 * /repos/{owner}/{repo}/releases/{id}/assets?name=}; the API lists no digest, so a same-named
 * attachment is compared by reading it through {@code /attachments/{uuid}}.
 */
public final class ReleaseForgejo extends ReleaseDraft {

    /** The line a 403 from the API stands for. */
    static final String SCOPE = "Forgejo refused the release; the token needs write:repository";

    private static final String JSON = "application/json";

    private final ForgejoRepository repo;
    private final URI repos;
    private final String token;

    /** {@code repos} is {@code {api}/repos/{owner}/{name}}; {@code commit} the tagged commit, if known. */
    ReleaseForgejo(Http http, ForgejoRepository repo, URI repos, String tag, String token, @Nullable String commit) {
        super(http, tag, commit);
        this.repo = repo;
        this.repos = repos;
        this.token = token;
    }

    /**
     * The release of {@code run}'s tag in {@code repo}, written with {@code token}.
     *
     * @throws IllegalStateException when the client resolved no token
     */
    static ReleaseForgejo open(ReleaseRun run, ForgejoRepository repo, @Nullable String token) {
        if (token == null || token.isBlank()) throw new IllegalStateException("no Forgejo token; " + repo.login());
        return new ReleaseForgejo(
                new Http(),
                repo,
                repo.repos(repo.api()),
                run.tag(),
                token,
                ReleaseForge.commitOf(run.root(), run.tag()));
    }

    @Override
    String forge() {
        return "Forgejo";
    }

    @Override
    String json() {
        return JSON;
    }

    @Override
    Map<String, String> headers(String accept) {
        return Map.of("Authorization", "token " + token, "Accept", accept, "User-Agent", "jk");
    }

    @Override
    URI releases() {
        return URI.create(repos + "/releases");
    }

    @Override
    URI releasesPage() {
        return URI.create(repos + "/releases?limit=50");
    }

    @Override
    URI assetsPage(long id) {
        return URI.create(repos + "/releases/" + id + "/assets");
    }

    @Override
    URI releaseUri(long id) {
        return URI.create(repos + "/releases/" + id);
    }

    @Override
    URI assetUri(long release, long asset) {
        return URI.create(repos + "/releases/" + release + "/assets/" + asset);
    }

    @Override
    Release parseRelease(Object r) {
        long id = ReleaseForge.number(r, "id");
        return new Release(id, Boolean.TRUE.equals(MiniJson.get(r, "draft")), repos + "/releases/" + id + "/assets");
    }

    @Override
    Asset parseAsset(Object a) {
        String uuid = MiniJson.str(a, "uuid");
        String download = MiniJson.str(a, "browser_download_url");
        URI bytes = uuid != null ? repo.attachment(uuid) : URI.create(String.valueOf(download));
        return new Asset(
                ReleaseForge.number(a, "id"),
                String.valueOf(MiniJson.str(a, "name")),
                ReleaseForge.number(a, "size"),
                true,
                null,
                bytes);
    }

    /**
     * Why Forgejo refused {@code what}, in one line: a rate limit, a token it does not take, the
     * scope a release needs, a repository it does not show, or an attachment over its size limit.
     */
    @Override
    IOException refused(String what, HttpResponse<?> response) {
        IOException later = ReleaseForge.retryLater("Forgejo", response);
        if (later != null) return later;
        return switch (response.statusCode()) {
            case 401 -> new IOException("Forgejo refused the token; " + repo.login());
            case 403 -> new IOException(SCOPE);
            case 404 -> new IOException("Forgejo shows no repository " + repo + " to this token");
            case 413 -> new IOException("Forgejo refused the " + what + ": larger than its attachment limit");
            default -> new IOException("Forgejo answered " + response.statusCode() + " to " + what);
        };
    }
}
