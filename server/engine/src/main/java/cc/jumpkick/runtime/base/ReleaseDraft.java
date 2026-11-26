// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.FileBody;
import cc.jumpkick.http.Http;
import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.jsonl.MiniJson;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A forge release of the tag that is a draft until the final run publishes it: GitHub's, and
 * Forgejo's and Gitea's, whose releases API has the same shape. Every upload checks the release's
 * asset list first, so a run that stopped halfway resumes: an asset with the same name, size and
 * SHA-256 is left alone, other bytes stop the run, and an asset a broken upload left unfinished is
 * deleted and sent again. A published release takes no new asset: a file it lacks is refused.
 *
 * <p>The draft is looked up by listing the repository's releases, since asking for the tag finds
 * only published ones. {@link #prepare} creates it before any module uploads.
 */
abstract class ReleaseDraft implements ReleaseStore {

    /** The release as the API last described it; {@code uploadUrl} takes {@code ?name=}. */
    record Release(long id, boolean draft, String uploadUrl) {}

    /**
     * One asset of the release. {@code digest} is {@code sha256:<hex>}, {@code null} when the forge
     * lists none; {@code bytes} is where its content downloads from.
     */
    record Asset(
            long id,
            String name,
            long size,
            boolean uploaded,
            @Nullable String digest,
            URI bytes) {}

    protected final Http http;
    protected final String tag;
    private final @Nullable String commit;
    private @Nullable Release release;
    private @Nullable List<Asset> assets;

    ReleaseDraft(Http http, String tag, @Nullable String commit) {
        this.http = http;
        this.tag = tag;
        this.commit = commit;
    }

    /** {@code GitHub} or {@code Forgejo}, for a message. */
    abstract String forge();

    /** The request headers: the credential, {@code Accept: accept} and what else the API wants. */
    abstract Map<String, String> headers(String accept);

    /** The {@code Accept} a JSON request sends. */
    abstract String json();

    /** {@code {repos}/releases}: listed, and posted to create the draft. */
    abstract URI releases();

    /** The first page of the releases list. */
    abstract URI releasesPage();

    /** The first page of release {@code id}'s assets. */
    abstract URI assetsPage(long id);

    /** Release {@code id}, patched to publish it. */
    abstract URI releaseUri(long id);

    /** Asset {@code asset} of release {@code release}, deleted when a broken upload left it. */
    abstract URI assetUri(long release, long asset);

    abstract Release parseRelease(Object json);

    abstract Asset parseAsset(Object json);

    /** Why the forge refused {@code what}, in one line that never shows the token. */
    abstract IOException refused(String what, HttpResponse<?> response);

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
        return find(name).filter(Asset::uploaded).isPresent();
    }

    @Override
    public Optional<Held> held(String name) throws IOException, InterruptedException {
        Optional<Asset> asset = find(name).filter(Asset::uploaded);
        if (asset.isEmpty()) return Optional.empty();
        return Optional.of(held(asset.get()));
    }

    /** The forge names each asset's download URL itself. */
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
        Release r = lookup().orElseThrow(() -> new IOException(forge() + " has no release " + tag + " to publish"));
        if (!r.draft()) return tag + " was already published";
        byte[] body = JsonFields.object().bool("draft", false).finish().getBytes(StandardCharsets.UTF_8);
        HttpResponse<byte[]> response = http.patch(releaseUri(r.id()), body, headers(json()));
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
        for (Object a : pages(assetsPage(r.get().id()))) out.add(parseAsset(a));
        List<Asset> listed = List.copyOf(out);
        assets = listed;
        return listed;
    }

    private Put put(String name, long size, ReleaseForge.Sha sha, ReleaseForge.Body body)
            throws IOException, InterruptedException {
        Release r = ensure();
        Optional<Asset> found = find(name);
        if (found.isPresent() && !found.get().uploaded()) {
            delete(r, found.get());
            found = Optional.empty();
        }
        if (found.isPresent()) return same(found.get(), name, size, sha);
        if (!r.draft()) throw new IOException(ReleaseRun.publishedWithout(tag, name));
        URI target = URI.create(r.uploadUrl() + "?name=" + URLEncoder.encode(name, StandardCharsets.UTF_8));
        Map<String, String> headers = new LinkedHashMap<>(headers(json()));
        headers.put("Content-Type", ReleaseStore.contentType(name));
        HttpResponse<byte[]> response = http.postOnce(target, body.get(), headers);
        assets = null;
        if (response.statusCode() == 201) {
            SessionContext.current().io().remoteUp(size);
            return Put.UPLOADED;
        }
        // An asset of that name may have arrived since the list was read.
        if (response.statusCode() == 422 || response.statusCode() == 409) {
            Optional<Asset> raced = find(name).filter(Asset::uploaded);
            if (raced.isPresent()) return same(raced.get(), name, size, sha);
        }
        throw refused("upload of " + name, response);
    }

    private Put same(Asset asset, String name, long size, ReleaseForge.Sha sha)
            throws IOException, InterruptedException {
        if (asset.size() == size && held(asset).sha256().equals(sha.get())) return Put.EXISTING;
        throw ReleaseForge.otherBytes(name, tag);
    }

    private Held held(Asset asset) throws IOException, InterruptedException {
        String digest = asset.digest();
        if (digest != null && digest.startsWith("sha256:")) {
            return new Held(asset.size(), digest.substring("sha256:".length()).toLowerCase(Locale.ROOT));
        }
        // No digest from this server: read the asset through once.
        return ReleaseForge.hash(
                http.getStream(asset.bytes(), headers("application/octet-stream")), forge(), "asset " + asset.name());
    }

    private void delete(Release r, Asset asset) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = http.delete(assetUri(r.id(), asset.id()), headers(json()));
        if (response.statusCode() != 204 && response.statusCode() != 404) {
            throw refused("DELETE of the broken asset " + asset.name(), response);
        }
        assets = null;
    }

    private Optional<Asset> find(String name) throws IOException, InterruptedException {
        return assets().stream().filter(a -> a.name().equals(name)).findFirst();
    }

    /** The release of the tag, created as a draft when there is none. */
    private Release ensure() throws IOException, InterruptedException {
        Optional<Release> known = lookup();
        if (known.isPresent()) return known.get();
        JsonFields fields = JsonFields.object().string("tag_name", tag).string("name", tag);
        if (commit != null) fields.string("target_commitish", commit);
        byte[] body = fields.bool("draft", true).finish().getBytes(StandardCharsets.UTF_8);
        HttpResponse<byte[]> response =
                http.postOnce(releases(), HttpRequest.BodyPublishers.ofByteArray(body), headers(json()));
        if (response.statusCode() != 201) throw refused("creating the draft release " + tag, response);
        Release created = release(response.body());
        release = created;
        assets = List.of();
        return created;
    }

    /** The release of the tag, draft or published, or empty when the forge has none. */
    private Optional<Release> lookup() throws IOException, InterruptedException {
        Release known = release;
        if (known != null) return Optional.of(known);
        for (Object r : pages(releasesPage())) {
            if (tag.equals(MiniJson.str(r, "tag_name"))) {
                Release found = parseRelease(r);
                release = found;
                return Optional.of(found);
            }
        }
        return Optional.empty();
    }

    private List<Object> pages(URI first) throws IOException, InterruptedException {
        return ReleaseForge.pages(http, first, headers(json()), this::refused);
    }

    private Release release(byte[] body) throws IOException {
        Object parsed = ReleaseForge.object(body);
        if (parsed == null) throw new IOException(forge() + " sent no release");
        return parseRelease(parsed);
    }
}
