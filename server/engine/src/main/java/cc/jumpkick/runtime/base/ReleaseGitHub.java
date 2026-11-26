// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.forge.GitHubRepository;
import cc.jumpkick.http.Http;
import cc.jumpkick.jsonl.MiniJson;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A GitHub Release of the tag, a {@link ReleaseDraft} until the final run publishes it. Assets
 * upload to the release's {@code upload_url}; GitHub lists each asset's SHA-256 as its {@code
 * digest}, and an asset whose {@code state} is not {@code uploaded} is one a broken upload left.
 */
public final class ReleaseGitHub extends ReleaseDraft {

    /**
     * Overrides the REST API root; GitHub Actions sets it to the repository's own server. Read from
     * the shell that ran {@code jk}, which forwards it ({@code RequestEnvNames.PUBLISH}).
     */
    public static final String API_ENV = "GITHUB_API_URL";

    /** The line a 403 or 404 from the API stands for. */
    static final String SCOPE = "GitHub refused the release; the token needs repo scope or contents: write";

    private static final String API_VERSION = "2022-11-28";
    private static final String JSON = "application/vnd.github+json";

    private final URI repos;
    private final URI uploads;
    private final String token;

    /**
     * {@code repos} is {@code {api}/repos/{owner}/{name}}, {@code uploads} the same under the upload
     * root (used when a release names no {@code upload_url}), and {@code commit} the commit the tag
     * is created at if GitHub does not have it yet.
     */
    ReleaseGitHub(Http http, URI repos, URI uploads, String tag, String token, @Nullable String commit) {
        super(http, tag, commit);
        this.repos = repos;
        this.uploads = uploads;
        this.token = token;
    }

    /**
     * The release of {@code run}'s tag in {@code repo}, written with {@code token}.
     *
     * @throws IllegalStateException when the client resolved no token
     */
    static ReleaseGitHub open(ReleaseRun run, GitHubRepository repo, @Nullable String token) {
        if (token == null || token.isBlank()) throw new IllegalStateException("no GitHub token; " + repo.login());
        String override = BuildEnv.get(API_ENV);
        URI api = override == null || override.isBlank() ? repo.api() : URI.create(override.strip());
        return new ReleaseGitHub(
                new Http(),
                repo.repos(api),
                repo.repos(repo.uploads()),
                run.tag(),
                token,
                ReleaseForge.commitOf(run.root(), run.tag()));
    }

    @Override
    String forge() {
        return "GitHub";
    }

    @Override
    String json() {
        return JSON;
    }

    @Override
    Map<String, String> headers(String accept) {
        return Map.of(
                "Authorization",
                "Bearer " + token,
                "Accept",
                accept,
                "X-GitHub-Api-Version",
                API_VERSION,
                "User-Agent",
                "jk");
    }

    @Override
    URI releases() {
        return URI.create(repos + "/releases");
    }

    @Override
    URI releasesPage() {
        return URI.create(repos + "/releases?per_page=100");
    }

    @Override
    URI assetsPage(long id) {
        return URI.create(repos + "/releases/" + id + "/assets?per_page=100");
    }

    @Override
    URI releaseUri(long id) {
        return URI.create(repos + "/releases/" + id);
    }

    @Override
    URI assetUri(long release, long asset) {
        return URI.create(repos + "/releases/assets/" + asset);
    }

    @Override
    Release parseRelease(Object r) {
        long id = ReleaseForge.number(r, "id");
        String upload = MiniJson.str(r, "upload_url");
        int brace = upload == null ? -1 : upload.indexOf('{');
        String url = upload == null
                ? uploads + "/releases/" + id + "/assets"
                : brace >= 0 ? upload.substring(0, brace) : upload;
        return new Release(id, Boolean.TRUE.equals(MiniJson.get(r, "draft")), url);
    }

    @Override
    Asset parseAsset(Object a) {
        long id = ReleaseForge.number(a, "id");
        return new Asset(
                id,
                String.valueOf(MiniJson.str(a, "name")),
                ReleaseForge.number(a, "size"),
                "uploaded".equals(MiniJson.str(a, "state")),
                MiniJson.str(a, "digest"),
                assetUri(-1, id));
    }

    @Override
    IOException refused(String what, HttpResponse<?> response) {
        return refusal(what, response);
    }

    /**
     * Why GitHub refused {@code what}, in one line: a rate limit with when to try again, a token it
     * does not take, or the scope a release needs. The token never appears.
     */
    static IOException refusal(String what, HttpResponse<?> response) {
        int status = response.statusCode();
        var headers = response.headers();
        boolean exhausted =
                headers.firstValue("x-ratelimit-remaining").map("0"::equals).orElse(false);
        Optional<String> retryAfter = headers.firstValue("retry-after");
        if ((status == 403 || status == 429) && (exhausted || retryAfter.isPresent())) {
            if (retryAfter.isPresent()) {
                return new IOException("GitHub rate limit reached; try again in "
                        + retryAfter.get().strip() + " s");
            }
            String reset = headers.firstValue("x-ratelimit-reset")
                    .map(ReleaseGitHub::clock)
                    .orElse(null);
            return new IOException(
                    "GitHub rate limit reached; try again " + (reset == null ? "later" : "after " + reset));
        }
        if (status == 401) {
            return new IOException("GitHub refused the token; run jk auth login github or set GH_TOKEN");
        }
        if (status == 403 || status == 404) return new IOException(SCOPE);
        return new IOException("GitHub answered " + status + " to " + what);
    }

    private static @Nullable String clock(String epochSeconds) {
        try {
            return DateTimeFormatter.ofPattern("HH:mm 'UTC'")
                    .withZone(ZoneOffset.UTC)
                    .format(Instant.ofEpochSecond(Long.parseLong(epochSeconds.strip())));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
