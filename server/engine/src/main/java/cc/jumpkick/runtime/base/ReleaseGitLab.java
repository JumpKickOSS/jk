// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.forge.GitLabProject;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.FileBody;
import cc.jumpkick.http.Http;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.release.ReleaseIndex;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A GitLab release of the tag. GitLab has no draft release and its release assets are links, so
 * every file goes to the project's generic package {@code <name>/<version>} first, where no release
 * points at it, and the final run creates the release with a link to each file of the package. A
 * file already in the package with the same size and SHA-256 is left alone and other bytes stop the
 * run. Once the release exists it takes no new file: one it lacks is refused.
 */
public final class ReleaseGitLab implements ReleaseStore {

    /** Overrides the REST API root; GitLab CI sets it to the instance's own. */
    public static final String API_ENV = "CI_API_V4_URL";

    /** The line a 403 from the API stands for. */
    static final String SCOPE = "GitLab refused the release; the token needs api scope and Developer";

    /** One file of the generic package; {@code sha256} is {@code null} when GitLab lists none. */
    record PackageFile(
            long id, String name, long size, @Nullable String sha256) {}

    private final Http http;
    private final GitLabProject repo;
    private final URI project;
    private final String packages;
    private final String name;
    private final String version;
    private final String tag;
    private final String token;
    private final boolean jobToken;
    private final @Nullable String commit;
    private @Nullable List<PackageFile> files;
    private @Nullable Set<String> links;
    private boolean looked;

    /**
     * The release {@code tag} of {@code repo} through {@code api}, its files in the generic package
     * {@code name}/{@code version}. A {@code jobToken} is GitLab CI's {@code CI_JOB_TOKEN}, sent as
     * {@code JOB-TOKEN}; any other token goes as a bearer token.
     */
    ReleaseGitLab(
            Http http,
            GitLabProject repo,
            URI api,
            String name,
            String version,
            String tag,
            String token,
            boolean jobToken,
            @Nullable String commit) {
        this.http = http;
        this.repo = repo;
        this.project = repo.project(api);
        this.packages = repo.packages(api, name, version);
        this.name = name;
        this.version = version;
        this.tag = tag;
        this.token = token;
        this.jobToken = jobToken;
        this.commit = commit;
    }

    /**
     * The release of {@code run}'s tag in {@code repo}, written with {@code token}.
     *
     * @throws IllegalStateException when the client resolved no token
     */
    static ReleaseGitLab open(ReleaseRun run, GitLabProject repo, @Nullable String token, boolean jobToken) {
        if (token == null || token.isBlank()) throw new IllegalStateException("no GitLab token; " + repo.login());
        String override = BuildEnv.get(API_ENV);
        URI api = override == null || override.isBlank() ? repo.api() : URI.create(override.strip());
        return new ReleaseGitLab(
                new Http(),
                repo,
                api,
                run.name(),
                run.version(),
                run.tag(),
                token,
                jobToken,
                ReleaseForge.commitOf(run.root(), run.tag()));
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

    /** True when the package holds {@code name} and, once the release exists, the release links it. */
    @Override
    public boolean present(String name) throws IOException, InterruptedException {
        if (find(name).isEmpty()) return false;
        Set<String> linked = linked();
        return linked == null || linked.contains(name);
    }

    @Override
    public Optional<Held> held(String name) throws IOException, InterruptedException {
        Optional<PackageFile> file = find(name);
        if (file.isEmpty()) return Optional.empty();
        return Optional.of(held(file.get()));
    }

    @Override
    public Optional<Held> fetch(String name, Path file) throws IOException, InterruptedException {
        Optional<PackageFile> found = find(name);
        if (found.isEmpty()) return Optional.empty();
        HttpResponse<InputStream> response = http.getStream(URI.create(url(name)), headers());
        if (response.statusCode() != 200) {
            response.body().close();
            throw refused("download of " + name, response);
        }
        return Optional.of(ReleaseForge.save(
                response.body(), file, found.get().size(), found.get().sha256(), name));
    }

    /** The file's download URL in the generic package, which the release's link points at too. */
    @Override
    public String url(String name) {
        return packages + encode(name);
    }

    /** The package is created by its first file, so nothing comes first. */
    @Override
    public void prepare() {}

    @Override
    public boolean published() throws IOException, InterruptedException {
        return linked() != null;
    }

    /** Create the release with a link to every file of the package. */
    @Override
    public String publish() throws IOException, InterruptedException {
        if (published()) return tag + " was already published";
        files = null;
        Map<String, PackageFile> latest = new LinkedHashMap<>();
        for (PackageFile f : files()) latest.put(f.name(), f);
        List<Object> assets = new ArrayList<>();
        for (String file : latest.keySet()) {
            Map<String, Object> link = new LinkedHashMap<>();
            link.put("name", file);
            link.put("url", url(file));
            link.put("direct_asset_path", "/" + file);
            link.put(
                    "link_type",
                    file.equals(ReleaseIndex.SUMS) || file.endsWith(".json") || file.endsWith(".asc")
                            ? "other"
                            : "package");
            assets.add(link);
        }
        Map<String, Object> release = new LinkedHashMap<>();
        release.put("tag_name", tag);
        release.put("name", tag);
        if (commit != null) release.put("ref", commit);
        release.put("assets", Map.of("links", assets));
        byte[] body = MiniJson.write(release).getBytes(StandardCharsets.UTF_8);
        HttpResponse<byte[]> response = http.postOnce(
                URI.create(project + "/releases"), HttpRequest.BodyPublishers.ofByteArray(body), headers());
        if (response.statusCode() == 409) {
            looked = false;
            if (published()) return tag + " was already published";
        }
        if (response.statusCode() != 201) throw refused("creating the release " + tag, response);
        links = Set.copyOf(latest.keySet());
        looked = true;
        return "published " + tag;
    }

    /** The package's files, oldest first; empty when there is no package yet. */
    List<PackageFile> files() throws IOException, InterruptedException {
        List<PackageFile> known = files;
        if (known != null) return known;
        List<PackageFile> out = new ArrayList<>();
        URI list = URI.create(project + "/packages?package_type=generic&package_name=" + encode(name)
                + "&package_version=" + encode(version) + "&per_page=100");
        for (Object p : pages(list)) {
            if (!name.equals(MiniJson.str(p, "name")) || !version.equals(MiniJson.str(p, "version"))) continue;
            long id = ReleaseForge.number(p, "id");
            for (Object f : pages(URI.create(project + "/packages/" + id + "/package_files?per_page=100"))) {
                String sha = MiniJson.str(f, "file_sha256");
                out.add(new PackageFile(
                        ReleaseForge.number(f, "id"),
                        String.valueOf(MiniJson.str(f, "file_name")),
                        ReleaseForge.number(f, "size"),
                        sha == null || sha.isBlank() ? null : sha.toLowerCase(Locale.ROOT)));
            }
        }
        List<PackageFile> listed = List.copyOf(out);
        files = listed;
        return listed;
    }

    private Put put(String name, long size, ReleaseForge.Sha sha, ReleaseForge.Body body)
            throws IOException, InterruptedException {
        Optional<PackageFile> found = find(name);
        if (found.isPresent()) return same(found.get(), name, size, sha);
        if (published()) throw new IOException(ReleaseRun.publishedWithout(tag, name));
        Map<String, String> headers = new LinkedHashMap<>(headers());
        headers.put("Content-Type", "application/octet-stream");
        HttpResponse<byte[]> response = http.put(URI.create(url(name)), body.get(), headers);
        files = null;
        if (response.statusCode() == 201 || response.statusCode() == 200) {
            SessionContext.current().io().remoteUp(size);
            return Put.UPLOADED;
        }
        // 400: the group refuses duplicate files and one of that name arrived since the list was read.
        if (response.statusCode() == 400) {
            Optional<PackageFile> raced = find(name);
            if (raced.isPresent()) return same(raced.get(), name, size, sha);
        }
        throw refused("upload of " + name, response);
    }

    private Put same(PackageFile file, String name, long size, ReleaseForge.Sha sha)
            throws IOException, InterruptedException {
        if (file.size() == size && held(file).sha256().equals(sha.get())) return Put.EXISTING;
        throw ReleaseForge.otherBytes(name, "the package " + this.name + " " + version);
    }

    private Held held(PackageFile file) throws IOException, InterruptedException {
        String sha = file.sha256();
        if (sha != null) return new Held(file.size(), sha);
        // No checksum from this server: read the file through once.
        return ReleaseForge.hash(http.getStream(URI.create(url(file.name())), headers()), "GitLab", file.name());
    }

    /** The latest file of that name: the one the package serves. */
    private Optional<PackageFile> find(String name) throws IOException, InterruptedException {
        PackageFile out = null;
        for (PackageFile f : files()) if (f.name().equals(name)) out = f;
        return Optional.ofNullable(out);
    }

    /** The names the release links, or {@code null} when there is no release of the tag. */
    private @Nullable Set<String> linked() throws IOException, InterruptedException {
        if (looked) return links;
        HttpResponse<byte[]> response = http.get(URI.create(project + "/releases/" + encode(tag)), headers());
        if (response.statusCode() == 404 && !projectMissing(response)) {
            links = null;
            looked = true;
            return null;
        }
        if (response.statusCode() != 200) throw refused("GET releases/" + tag, response);
        Set<String> names = new LinkedHashSet<>();
        Object assets = MiniJson.get(ReleaseForge.object(response.body()), "assets");
        for (Object link : MiniJson.list(assets, "links")) {
            String n = MiniJson.str(link, "name");
            if (n != null) names.add(n);
        }
        Set<String> found = Set.copyOf(names);
        links = found;
        looked = true;
        return found;
    }

    /** True when a 404 is GitLab hiding the project, not a missing release. */
    private static boolean projectMissing(HttpResponse<byte[]> response) {
        return new String(response.body(), StandardCharsets.UTF_8).contains("Project Not Found");
    }

    private List<Object> pages(URI first) throws IOException, InterruptedException {
        return ReleaseForge.pages(http, first, headers(), this::refused);
    }

    private Map<String, String> headers() {
        return Map.of(
                jobToken ? "JOB-TOKEN" : "Authorization",
                jobToken ? token : "Bearer " + token,
                "Accept",
                "application/json",
                "User-Agent",
                "jk");
    }

    private static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * Why GitLab refused {@code what}, in one line: a rate limit, a token it does not take, the
     * scope and role a release needs, a project it does not show, or a file over its size limit.
     */
    IOException refused(String what, HttpResponse<?> response) {
        IOException later = ReleaseForge.retryLater("GitLab", response);
        if (later != null) return later;
        return switch (response.statusCode()) {
            case 401 -> new IOException("GitLab refused the token; " + repo.login());
            case 403 -> new IOException(SCOPE);
            case 404 -> new IOException("GitLab shows no project " + repo + " to this token");
            case 413 -> new IOException("GitLab refused the " + what + ": larger than its package limit");
            default -> new IOException("GitLab answered " + response.statusCode() + " to " + what);
        };
    }
}
