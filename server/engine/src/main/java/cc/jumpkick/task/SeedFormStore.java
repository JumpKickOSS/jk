// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.CacheBackend;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The store a request keeps its suite results' shared forms in when the team set a cache key seed
 * ({@link CacheSeeds}) and no remote cache is named: the request's own action cache, so a cache
 * directory restored on another machine (a CI runner's own cache) replays there a result that read
 * the environment when that machine sets the same seed ({@link RemoteCacheBinding#forRequest}).
 *
 * <p>Behind a tier that asks and stores shared forms alone ({@link RemoteTier#sharedFormsOnly}): a
 * result keyed on this home's secret is kept under its own key as always, and beside it, in the
 * same {@code actions/keys/} and CAS, under its key's shared form, the form a remote cache would be
 * sent (no host mark, no value, no digest of one). A lookup that misses its own key asks the shared
 * form next and keeps what it finds under its own key. Nothing else is asked or stored twice.
 *
 * <p>The shared form is keyed under the seed derived for {@link #SCOPE}, a fixed word: two runners
 * with other checkout paths, homes and users key a value alike, and the project id the key already
 * holds keeps projects apart. Bound to the request's action cache by the first one the request
 * opens ({@link #bind}); until then, and if none opens, it answers every call as unreachable.
 */
final class SeedFormStore implements CacheBackend {

    /** The scope the team's seed is derived under for this store: no URL or path, so every machine takes it alike. */
    static final String SCOPE = "local cache";

    private volatile @Nullable LocalCacheBackend disk;

    /** Keep the shared forms in {@code local}, the request's action cache, unless one is bound already. */
    void bind(LocalCacheBackend local) {
        if (disk == null) disk = local;
    }

    private LocalCacheBackend disk() throws IOException {
        LocalCacheBackend bound = disk;
        if (bound == null) throw new IOException("no action cache is open");
        return bound;
    }

    @Override
    public String name() {
        return "local (team seed)";
    }

    @Override
    public String seedScope() {
        return SCOPE;
    }

    @Override
    public Map<String, String> records(Lookup lookup) throws IOException {
        return disk().records(lookup);
    }

    @Override
    public Set<String> missing(Map<String, Long> blobs) throws IOException {
        return disk().missing(blobs);
    }

    @Override
    public void fetch(Map<String, Long> blobs, BlobSink sink) throws IOException {
        disk().fetch(blobs, sink);
    }

    @Override
    public void putBlobs(Map<String, Path> blobs) throws IOException {
        disk().putBlobs(blobs);
    }

    @Override
    public void putRecord(String key, String recordText) throws IOException {
        disk().putRecord(key, recordText);
    }
}
