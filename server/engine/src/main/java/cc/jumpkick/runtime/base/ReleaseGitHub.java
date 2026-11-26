// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.JobWorkers;
import cc.jumpkick.forge.GitHubRepository;
import cc.jumpkick.host.GitCommand;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Processes;
import cc.jumpkick.http.FileBody;
import cc.jumpkick.http.Http;
import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.jsonl.MiniJson;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A GitHub Release of the tag: a draft until the final run publishes it. Every upload checks the
 * release's asset list first, so a run that stopped halfway resumes: an asset with the same name,
 * size and SHA-256 is left alone, other bytes stop the run, and an asset a broken upload left empty
 * is deleted and sent again. A published release takes no new asset: a file it lacks is refused.
 *
 * <p>The draft is looked up by listing the repository's releases, since asking for the tag finds
 * only published ones. {@link #prepare} creates it before any module uploads.
 */
public final class ReleaseGitHub implements ReleaseStore {

    /** Overrides the REST API root; GitHub Actions sets it to the repository's own server. */
    public static final String API_ENV = "GITHUB_API_URL";

    /** The line a 403 or 404 from the API stands for. */
    static final String SCOPE = "GitHub refused the release; the token needs repo scope or contents: write";

    private static final String API_VERSION = "2022-11-28";
    private static final String JSON = "application/vnd.github+json";
    private static final int MAX_PAGES = 50;
    private static final Pattern NEXT = Pattern.compile("<([^>]+)>\\s*;\\s*rel=\"next\"");
    private static final Duration GIT_WAIT = Duration.ofSeconds(10);

    /** The release as the API last described it. */
    record Release(long id, boolean draft, String uploadUrl) {}

    /** One asset of the release; {@code digest} is {@code sha256:<hex>}, {@code null} when GitHub gives none. */
    record Asset(
            long id,
            String name,
            long size,
            String state,
            @Nullable String digest) {
        boolean uploaded() {
            return "uploaded".equals(state);
        }
    }

    private final Http http;
    private final URI repos;
    private final URI uploads;
    private final String tag;
    private final String token;
    private final @Nullable String commit;
    private @Nullable Release release;
    private @Nullable List<Asset> assets;

    /**
     * {@code repos} is {@code {api}/repos/{owner}/{name}}, {@code uploads} the same under the upload
     * root (used when a release names no {@code upload_url}), and {@code commit} the commit the tag
     * is created at if GitHub does not have it yet.
     */
    ReleaseGitHub(Http http, URI repos, URI uploads, String tag, String token, @Nullable String commit) {
        this.http = http;
        this.repos = repos;
        this.uploads = uploads;
        this.tag = tag;
        this.token = token;
        this.commit = commit;
    }

    /**
     * The release of {@code run}'s tag in the repository its origin names, written with {@code
     * token}.
     *
     * @throws IllegalStateException when the client resolved no token
     */
    static ReleaseGitHub open(ReleaseRun run, @Nullable String token) {
        GitHubRepository repo = Objects.requireNonNull(run.github(), "github");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("no GitHub token; run jk auth login github or set GH_TOKEN");
        }
        String override = BuildEnv.get(API_ENV);
        URI api = override == null || override.isBlank() ? repo.api() : URI.create(override.strip());
        return new ReleaseGitHub(
                new Http(),
                repo.repos(api),
                repo.repos(repo.uploads()),
                run.tag(),
                token,
                commitOf(run.root(), run.tag()));
    }

    @Override
    public Put put(String name, Path file) throws IOException, InterruptedException {
        return put(name, Files.size(file), () -> Hashing.sha256Hex(file), () -> FileBody.of(file));
    }

    @Override
    public Put put(String name, byte[] body) throws IOException, InterruptedException {
        return put(
                name, body.length, () -> Hashing.sha256Hex(body), () -> HttpRequest.BodyPublishers.ofByteArray(body));
    }

    @Override
    public boolean present(String name) throws IOException, InterruptedException {
        return asset(name).filter(Asset::uploaded).isPresent();
    }

    @Override
    public Optional<Held> held(String name) throws IOException, InterruptedException {
        Optional<Asset> asset = asset(name).filter(Asset::uploaded);
        if (asset.isEmpty()) return Optional.empty();
        return Optional.of(held(asset.get()));
    }

    /** GitHub names each asset's download URL itself. */
    @Override
    public @Nullable String url(String name) {
        return null;
    }

    /** Find the tag's release, or create its draft. */
    @Override
    public void prepare() throws IOException, InterruptedException {
        ensure();
    }

    @Override
    public boolean published() throws IOException, InterruptedException {
        return lookup().map(r -> !r.draft()).orElse(false);
    }

    @Override
    public String publish() throws IOException, InterruptedException {
        Release r = lookup().orElseThrow(() -> new IOException("GitHub has no release " + tag + " to publish"));
        if (!r.draft()) return tag + " was already published";
        byte[] body = JsonFields.object().bool("draft", false).finish().getBytes(StandardCharsets.UTF_8);
        HttpResponse<byte[]> response = http.patch(URI.create(repos + "/releases/" + r.id()), body, headers(JSON));
        if (response.statusCode() != 200) throw refused("PATCH releases/" + r.id(), response);
        release = release(response.body());
        return "published " + tag;
    }

    /** The release's asset list, or empty when there is no release yet. */
    List<Asset> assets() throws IOException, InterruptedException {
        List<Asset> known = assets;
        if (known != null) return known;
        Optional<Release> r = lookup();
        if (r.isEmpty()) return List.of();
        List<Asset> out = new ArrayList<>();
        for (Object a : pages(URI.create(repos + "/releases/" + r.get().id() + "/assets?per_page=100"))) {
            out.add(new Asset(
                    number(a, "id"),
                    String.valueOf(MiniJson.str(a, "name")),
                    number(a, "size"),
                    String.valueOf(MiniJson.str(a, "state")),
                    MiniJson.str(a, "digest")));
        }
        List<Asset> listed = List.copyOf(out);
        assets = listed;
        return listed;
    }

    private interface Sha {
        String get() throws IOException;
    }

    private interface Body {
        HttpRequest.BodyPublisher get() throws IOException;
    }

    private Put put(String name, long size, Sha sha, Body body) throws IOException, InterruptedException {
        Release r = ensure();
        Optional<Asset> found = asset(name);
        if (found.isPresent() && !found.get().uploaded()) {
            delete(found.get());
            found = Optional.empty();
        }
        if (found.isPresent()) return same(found.get(), name, size, sha);
        if (!r.draft()) throw new IOException(ReleaseRun.publishedWithout(tag, name));
        URI target = URI.create(r.uploadUrl() + "?name=" + URLEncoder.encode(name, StandardCharsets.UTF_8));
        Map<String, String> headers = new LinkedHashMap<>(headers(JSON));
        headers.put("Content-Type", ReleaseStore.contentType(name));
        HttpResponse<byte[]> response = http.postOnce(target, body.get(), headers);
        assets = null;
        if (response.statusCode() == 201) {
            SessionContext.current().io().remoteUp(size);
            return Put.UPLOADED;
        }
        // 422: an asset of that name arrived since the list was read.
        if (response.statusCode() == 422) {
            Optional<Asset> raced = asset(name).filter(Asset::uploaded);
            if (raced.isPresent()) return same(raced.get(), name, size, sha);
        }
        throw refused("upload of " + name, response);
    }

    private Put same(Asset asset, String name, long size, Sha sha) throws IOException, InterruptedException {
        if (asset.size() == size && held(asset).sha256().equals(sha.get())) return Put.EXISTING;
        throw new IOException(name + " is already in " + tag + " with other bytes; raise the version in jk.toml");
    }

    private Held held(Asset asset) throws IOException, InterruptedException {
        String digest = asset.digest();
        if (digest != null && digest.startsWith("sha256:")) {
            return new Held(asset.size(), digest.substring("sha256:".length()).toLowerCase(Locale.ROOT));
        }
        // No digest from this server: read the asset through once.
        HttpResponse<InputStream> response = http.getStream(
                URI.create(repos + "/releases/assets/" + asset.id()), headers("application/octet-stream"));
        try (InputStream in = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("GitHub answered " + response.statusCode() + " for asset " + asset.name());
            }
            MessageDigest sha = Hashing.newSha256();
            long size = 0;
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0; ) {
                sha.update(buffer, 0, n);
                size += n;
            }
            return new Held(size, Hashing.hex(sha.digest()));
        }
    }

    private void delete(Asset asset) throws IOException, InterruptedException {
        HttpResponse<byte[]> response =
                http.delete(URI.create(repos + "/releases/assets/" + asset.id()), headers(JSON));
        if (response.statusCode() != 204 && response.statusCode() != 404) {
            throw refused("DELETE of the broken asset " + asset.name(), response);
        }
        assets = null;
    }

    private Optional<Asset> asset(String name) throws IOException, InterruptedException {
        return assets().stream().filter(a -> a.name().equals(name)).findFirst();
    }

    /** The release of the tag, created as a draft when there is none. */
    private Release ensure() throws IOException, InterruptedException {
        Optional<Release> known = lookup();
        if (known.isPresent()) return known.get();
        JsonFields fields = JsonFields.object().string("tag_name", tag).string("name", tag);
        if (commit != null) fields.string("target_commitish", commit);
        byte[] body = fields.bool("draft", true).finish().getBytes(StandardCharsets.UTF_8);
        HttpResponse<byte[]> response = http.postOnce(
                URI.create(repos + "/releases"), HttpRequest.BodyPublishers.ofByteArray(body), headers(JSON));
        if (response.statusCode() != 201) throw refused("creating the draft release " + tag, response);
        Release created = release(response.body());
        release = created;
        assets = List.of();
        return created;
    }

    /** The release of the tag, draft or published, or empty when GitHub has none. */
    private Optional<Release> lookup() throws IOException, InterruptedException {
        Release known = release;
        if (known != null) return Optional.of(known);
        for (Object r : pages(URI.create(repos + "/releases?per_page=100"))) {
            if (tag.equals(MiniJson.str(r, "tag_name"))) {
                Release found = release(r);
                release = found;
                return Optional.of(found);
            }
        }
        return Optional.empty();
    }

    /** Every element of a paged JSON array, following {@code Link: rel="next"}. */
    private List<Object> pages(URI first) throws IOException, InterruptedException {
        List<Object> out = new ArrayList<>();
        URI next = first;
        for (int page = 0; next != null && page < MAX_PAGES; page++) {
            HttpResponse<byte[]> response = http.get(next, headers(JSON));
            if (response.statusCode() != 200) throw refused("GET " + next.getPath(), response);
            Object parsed = MiniJson.parseRelaxed(new String(response.body(), StandardCharsets.UTF_8));
            if (parsed instanceof List<?> list) out.addAll(list);
            next = response.headers()
                    .firstValue("link")
                    .map(NEXT::matcher)
                    .filter(Matcher::find)
                    .map(m -> URI.create(m.group(1)))
                    .orElse(null);
        }
        return out;
    }

    private Release release(byte[] body) throws IOException {
        Object parsed = MiniJson.parseRelaxed(new String(body, StandardCharsets.UTF_8));
        if (parsed == null) throw new IOException("GitHub sent no release");
        return release(parsed);
    }

    private Release release(Object r) {
        long id = number(r, "id");
        String upload = MiniJson.str(r, "upload_url");
        int brace = upload == null ? -1 : upload.indexOf('{');
        String url = upload == null
                ? uploads + "/releases/" + id + "/assets"
                : brace >= 0 ? upload.substring(0, brace) : upload;
        return new Release(id, Boolean.TRUE.equals(MiniJson.get(r, "draft")), url);
    }

    private static long number(Object node, String key) {
        return MiniJson.get(node, key) instanceof Number n ? n.longValue() : -1;
    }

    private Map<String, String> headers(String accept) {
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

    /**
     * Why GitHub refused {@code what}, in one line: a rate limit with when to try again, a token it
     * does not take, or the scope a release needs. The token never appears.
     */
    static IOException refused(String what, HttpResponse<?> response) {
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
        if (status == 401) return new IOException("GitHub refused the token; run jk auth login github or set GH_TOKEN");
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

    /** The commit {@code tag} names in {@code dir}'s checkout, else {@code HEAD}'s; {@code null} when git cannot say. */
    static @Nullable String commitOf(Path dir, String tag) {
        for (String rev : List.of(tag + "^{commit}", "HEAD")) {
            try {
                ProcessBuilder pb = new ProcessBuilder(GitCommand.of(
                                "-C", dir.toString(), "rev-parse", "--verify", "--quiet", "--end-of-options", rev))
                        .redirectError(ProcessBuilder.Redirect.DISCARD);
                Processes.Captured c = Processes.capture(pb, JobWorkers.startDetached(pb), GIT_WAIT);
                String out = c.output().strip();
                if (c.ok() && out.matches("[0-9a-f]{40}([0-9a-f]{24})?")) return out;
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }
}
