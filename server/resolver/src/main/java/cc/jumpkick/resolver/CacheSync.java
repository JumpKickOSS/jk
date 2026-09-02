// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkM2Config;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.RepoSource;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.repo.ArtifactLocator;
import cc.jumpkick.repo.M2Dirs;
import cc.jumpkick.repo.MavenLayout;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoArtifactResolver;
import cc.jumpkick.repo.RepoCredentialResolver;
import cc.jumpkick.run.JkThreads;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Ensures every lockfile sha256 is on disk as a Maven-layout {@code *.jar} (Maven local repo
 * and/or {@code repos/<name>/}). Parallel on {@link JkThreads#io}; per-host concurrency is capped
 * inside {@link MavenRepo}. Checksum mismatches are reported, never accepted.
 */
public final class CacheSync {

    private final Cas cas;
    private final Http http;
    private final RepoCredentialResolver creds;
    private final boolean m2integration;
    private final ArtifactLocator locator;

    public CacheSync(Cas cas, Http http) {
        this(cas, http, new RepoCredentialResolver(), true);
    }

    /** As above, with the resolving project's {@code m2integration} value. */
    public CacheSync(Cas cas, Http http, boolean m2integration) {
        this(cas, http, new RepoCredentialResolver(), m2integration);
    }

    /** Visible for tests — inject a credential resolver. */
    public CacheSync(Cas cas, Http http, RepoCredentialResolver creds) {
        this(cas, http, creds, true);
    }

    /** Visible for tests — inject a credential resolver and {@code m2integration} explicitly. */
    public CacheSync(Cas cas, Http http, RepoCredentialResolver creds, boolean m2integration) {
        this.cas = Objects.requireNonNull(cas, "cas");
        this.http = Objects.requireNonNull(http, "http");
        this.creds = Objects.requireNonNull(creds, "creds");
        // Effective policy is project AND the machine kill switch — mirror MavenRepo, so a global
        // [m2] integration = false is honored here too.
        boolean effectiveM2 = m2integration && JkM2Config.resolve().integration();
        this.m2integration = effectiveM2;
        this.locator = new ArtifactLocator(cas.root(), effectiveM2 ? M2Dirs.localRepository() : null, effectiveM2);
    }

    /**
     * Counts the artifacts in {@code lock} that will be processed by {@link #sync} — i.e.,
     * those with a non-null checksum (POM-only, path, and git deps are skipped). Used to
     * pre-compute the bar denominator before the sync starts.
     */
    public static int countArtifacts(Lockfile lock) {
        int count = 0;
        for (Lockfile.Artifact pkg : lock.artifacts()) {
            if (pkg.checksum() != null) count++;
        }
        return count;
    }

    public Report sync(Lockfile lock) throws IOException, InterruptedException {
        return sync(lock, ProgressObserver.NOOP, false);
    }

    public Report sync(Lockfile lock, ProgressObserver observer) throws IOException, InterruptedException {
        return sync(lock, observer, false);
    }

    /**
     * Sync with per-package progress. {@code refresh} forces re-download even when a verified
     * jar is already on disk.
     */
    public Report sync(Lockfile lock, ProgressObserver observer, boolean refresh)
            throws IOException, InterruptedException {
        int upToDate = 0;
        int skipped = 0;
        // Build the list of fetches to do, resolving repos up front (the
        // repoCache HashMap below isn't thread-safe; precomputing keeps it
        // single-writer).
        Map<String, MavenRepo> repoCache = new HashMap<>();
        List<PendingFetch> pending = new ArrayList<>();
        for (Lockfile.Artifact pkg : lock.artifacts()) {
            if (pkg.checksum() == null) {
                skipped++; // POM-only / path / git deps
                observer.skipped(pkg);
                continue;
            }
            String hex = pkg.checksumHex();

            if (!refresh && locator.locate(pkg).isPresent()) {
                upToDate++;
                observer.upToDate(pkg);
                continue;
            }
            // First-party store source (jk install <file>, worker JARs) is never fetched from a
            // remote — it lives in repos/jk-local.
            if (RepoArtifactResolver.isFirstPartySource(pkg.source())) {
                if (locator.locate(pkg).isPresent()) upToDate++;
                else skipped++;
                observer.upToDate(pkg);
                continue;
            }
            pending.add(new PendingFetch(pkg, hex, repoFor(pkg.source(), repoCache)));
        }

        // Dispatch all fetches concurrently. MavenRepo rate-limits the network leg.
        List<CompletableFuture<FetchResult>> futures = new ArrayList<>(pending.size());
        for (PendingFetch p : pending) {
            CompletableFuture<FetchResult> fut = CompletableFuture.supplyAsync(() -> fetch(p), JkThreads.io());
            // Fire the per-package callback on the fetcher's completion
            // thread so the progress bar updates as parallel fetches
            // finish, not in a single end-of-pass burst. thenAccept
            // returns a new future we don't track — the original `fut`
            // is what we collect below; the notification side-effect
            // happens before the parent join.
            fut.thenAccept(result -> {
                if (result.error != null) observer.failed(p.pkg, result.error);
                else observer.fetched(p.pkg);
            });
            futures.add(fut);
        }

        int fetched = 0;
        List<String> errors = new ArrayList<>();
        for (CompletableFuture<FetchResult> f : futures) {
            FetchResult r;
            try {
                r = f.get();
            } catch (ExecutionException e) {
                // Unwrap unexpected throwables from supplyAsync.
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                errors.add(cause.getMessage());
                continue;
            }
            if (r.error != null) errors.add(r.error);
            else fetched++;
        }

        return new Report(fetched, upToDate, skipped, List.copyOf(errors));
    }

    /**
     * Fetch sources JARs for every locked package that has a {@code sources-checksum} field.
     * Already-cached sources are skipped. Packages without a sources checksum are silently ignored.
     *
     * @return count of sources JARs fetched (not counting those already cached)
     */
    public int syncSources(Lockfile lock, ProgressObserver observer) throws IOException, InterruptedException {
        Map<String, MavenRepo> repoCache = new HashMap<>();
        List<PendingFetch> pending = new ArrayList<>();
        for (Lockfile.Artifact pkg : lock.artifacts()) {
            if (pkg.sourcesChecksum() == null) continue;
            String hex = pkg.sourcesChecksumHex();
            Coordinate sourcesCoord =
                    new Coordinate(pkg.moduleGroup(), pkg.moduleArtifact(), pkg.version(), "sources", "jar");
            String repoName = RepoArtifactResolver.repoName(pkg.source());
            String rel = MavenLayout.artifactPath(sourcesCoord);
            if (locator.locate(repoName, rel, hex, sourcesCoord.toGav()).isPresent()) {
                observer.upToDate(pkg);
                continue;
            }
            // Reuse the existing repoFor with the package's original source.
            try {
                pending.add(new PendingFetch(pkg, hex, repoFor(pkg.source(), repoCache)));
            } catch (IllegalArgumentException ignored) {
            } // non-maven source
        }

        int fetched = 0;
        List<CompletableFuture<FetchResult>> futures = new ArrayList<>();
        for (PendingFetch p : pending) {
            futures.add(CompletableFuture.supplyAsync(() -> fetchSources(p), JkThreads.io()));
        }
        for (int i = 0; i < futures.size(); i++) {
            FetchResult r;
            try {
                r = futures.get(i).get();
            } catch (ExecutionException e) {
                observer.failed(pending.get(i).pkg, e.getMessage());
                continue;
            }
            if (r.error() == null) {
                fetched++;
                observer.fetched(pending.get(i).pkg);
            } else {
                observer.failed(pending.get(i).pkg, r.error());
            }
        }
        return fetched;
    }

    private static FetchResult fetchSources(PendingFetch p) {
        Coordinate sourcesCoord =
                new Coordinate(p.pkg.moduleGroup(), p.pkg.moduleArtifact(), p.pkg.version(), "sources", "jar");
        try {
            MavenRepo.Fetched f = p.repo.fetchArtifact(sourcesCoord);
            if (!f.sha256().equals(p.expectedHex)) {
                return FetchResult.failure(p.pkg.name() + " sources: checksum mismatch");
            }
            return FetchResult.ok();
        } catch (IOException e) {
            return FetchResult.failure(p.pkg.name() + " sources: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return FetchResult.failure(p.pkg.name() + " sources: interrupted");
        }
    }

    /**
     * Per-package callback driven by {@link #sync(Lockfile, ProgressObserver)}. Methods are invoked
     * once per package, on whatever thread resolved that package's outcome. Implementations need to
     * be thread-safe.
     */
    public interface ProgressObserver {
        ProgressObserver NOOP = new ProgressObserver() {};

        default void upToDate(Lockfile.Artifact pkg) {}

        default void fetched(Lockfile.Artifact pkg) {}

        default void skipped(Lockfile.Artifact pkg) {}

        default void failed(Lockfile.Artifact pkg, String error) {}
    }

    private static FetchResult fetch(PendingFetch p) {
        Coordinate coord = toCoord(p.pkg);
        try {
            // Rate limit lives in MavenRepo.fetch (network leg only). Wrapping again deadlocks the
            // non-reentrant HostRateLimiter once concurrent fetchers hold all permits.
            // Pass the pin so a stale local-mirror copy that no longer matches (republished GAV +
            // re-lock) is evicted and re-fetched, rather than dead-ending in a checksum mismatch that
            // never touches the network.
            MavenRepo.Fetched f = p.repo.fetchArtifact(coord, p.expectedHex, () -> false);
            if (!f.sha256().equals(p.expectedHex)) {
                return FetchResult.failure(p.pkg.name()
                        + " v"
                        + p.pkg.version()
                        + ": checksum mismatch — lock says "
                        + p.expectedHex
                        + ", got "
                        + f.sha256());
            }
            return FetchResult.ok();
        } catch (IOException e) {
            return FetchResult.failure(p.pkg.name() + " v" + p.pkg.version() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return FetchResult.failure(p.pkg.name() + " v" + p.pkg.version() + ": interrupted");
        }
    }

    private MavenRepo repoFor(String source, Map<String, MavenRepo> cache) {
        MavenRepo existing = cache.get(source);
        if (existing != null) return existing;
        RepoSource rs = RepoSource.parse(source);
        String name = rs.name();
        if (name == null) {
            throw new IllegalArgumentException("lockfile package source must be '<name>+<url>', got: " + source);
        }
        URI url = URI.create(rs.url());
        var cred = creds.resolve(name, url, Optional.empty());
        MavenRepo repo = new MavenRepo(name, url, http, cas, cred, m2integration);
        cache.put(source, repo);
        return repo;
    }

    private static Coordinate toCoord(Lockfile.Artifact pkg) {
        return pkg.coordinate();
    }

    /** A package whose jar is missing on disk and needs to be fetched. */
    private record PendingFetch(Lockfile.Artifact pkg, String expectedHex, MavenRepo repo) {}

    /** Outcome of one parallel fetch — null error means success. */
    private record FetchResult(String error) {
        static FetchResult ok() {
            return new FetchResult(null);
        }

        static FetchResult failure(String msg) {
            return new FetchResult(msg);
        }
    }

    public record Report(int fetched, int upToDate, int skipped, List<String> errors) {
        public Report {
            errors = List.copyOf(errors);
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }
    }
}
