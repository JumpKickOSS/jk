// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.http.Http;
import cc.jumpkick.http.RateLimitedException;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.DownloadSlots;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.run.JkThreads;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The import-time check of every exact pin a POM wrote: a version no repository the lock reads
 * lists is a Tier-3 row here, where the POM is still in front of the user, rather than a refusal
 * at {@code jk lock}. One catalog walk per pinned {@code group:artifact} — however many versions
 * of it the reactor's modules pin — over the same {@code maven-metadata.xml} the lock reads and in
 * the lock's repository order, stopping at the first repository that lists every version asked; a
 * repository that cannot be reached makes the pin a note, because an absent answer is not an
 * absent version. A pin whose POM one of those repositories' stores already mirrors — a lock
 * fetched it before — is not walked at all: the mirrored POM is the repository's own word that the
 * version exists. The sweep runs {@link #walksInFlight()} catalogs at a time and under a wall
 * budget ({@link #BUDGET}): what it has not reached by then is one note naming the count, and
 * {@code jk lock} decides those pins as it decides every other. A repository that fails to answer
 * — a 401 or 429 to a request, a connection nothing accepts — is asked once per sweep
 * ({@link Refusals}): the first walk to reach it records the answer and every later walk notes the
 * repository without a request, so a refusing repository costs one round trip, not one per pin.
 */
public final class DeclaredPins {

    /**
     * The fewest catalog walks in flight at once; the host's download width raises it. A catalog is
     * a few kilobytes, so what bounds the sweep is round trips, not bytes.
     */
    private static final int WALKS_IN_FLIGHT_FLOOR = 8;

    /** The sweep's wall budget; a thousand-coordinate reactor is checked within it or noted. */
    static final Duration BUDGET = Duration.ofSeconds(90);

    /** How many of a catalog's versions a row names before eliding the rest. */
    private static final int VERSIONS_NAMED = 8;

    private DeclaredPins() {}

    /**
     * {@code report} plus one row per pinned version no repository the lock reads lists and one
     * note per pin that could not be checked. {@code modules} are the workspace members by
     * root-relative path; {@code root} is the standalone project or the workspace root, whose
     * {@code [repositories]} join the repositories {@code importer} was built over.
     */
    public static ImportReport check(
            JkBuild root, Map<String, JkBuild> modules, ImportReport report, PomImporter importer) {
        RepoGroup base = importer.resolver.repos();
        return check(root, modules, report, lockRepos(base, root, importer.resolver.cas()), Clock.SYSTEM, BUDGET);
    }

    /** Catalog walks in flight at once: half the host's download width, {@link #WALKS_IN_FLIGHT_FLOOR} at least. */
    static int walksInFlight() {
        return Math.max(WALKS_IN_FLIGHT_FLOOR, DownloadSlots.width() / 2);
    }

    /**
     * The repositories the lock of {@code imported} reads, in the lock's order: every
     * {@code [repositories]} entry the import wrote onto it, under its release/snapshot policy,
     * ahead of {@code base}.
     */
    static RepoGroup lockRepos(RepoGroup base, JkBuild imported, Cas cas) {
        List<MavenRepo> declared = new ArrayList<>();
        Http http = Http.forRepositories();
        for (RepositorySpec spec : imported.repositories()) {
            if (base.repos().stream().anyMatch(r -> r.baseUrl().equals(spec.url()))) continue;
            declared.add(
                    new MavenRepo(spec.name(), spec.url(), http, cas).withPolicy(spec.releases(), spec.snapshots()));
        }
        return base.withReposPrepended(declared);
    }

    /** {@link #check(JkBuild, Map, ImportReport, PomImporter)} over exactly {@code repos}, under the standard budget. */
    static ImportReport check(JkBuild root, Map<String, JkBuild> modules, ImportReport report, RepoGroup repos) {
        return check(root, modules, report, repos, Clock.SYSTEM, BUDGET);
    }

    /** {@link #check(JkBuild, Map, ImportReport, RepoGroup)} with the sweep's budget read off {@code clock}. */
    static ImportReport check(
            JkBuild root,
            Map<String, JkBuild> modules,
            ImportReport report,
            RepoGroup repos,
            Clock clock,
            Duration budget) {
        Map<String, List<String>> ownersByGav = new LinkedHashMap<>();
        collect("", root, ownersByGav);
        for (Map.Entry<String, JkBuild> e : modules.entrySet()) collect(e.getKey(), e.getValue(), ownersByGav);
        if (ownersByGav.isEmpty()) return report;
        Sweep sweep = walkAll(ownersByGav.keySet(), repos, clock, budget);
        ImportReport.Builder out = ImportReport.builder();
        for (ImportReport.Issue issue : report.issues()) {
            if (issue.severity() == ImportReport.Severity.ERROR) out.error(issue.message());
            else out.warning(issue.message());
        }
        ModuleRows rows = new ModuleRows();
        for (Map.Entry<String, List<String>> e : ownersByGav.entrySet()) {
            Verdict verdict = sweep.verdicts().get(e.getKey());
            if (verdict == null || verdict.listed()) continue;
            ImportReport.Severity severity =
                    verdict.unreachable().isEmpty() ? ImportReport.Severity.ERROR : ImportReport.Severity.WARNING;
            String row = verdict.render(e.getKey());
            for (String owner : e.getValue()) rows.add(owner, severity, row);
        }
        rows.flush(out);
        if (!sweep.unchecked().isEmpty()) out.warning(sweep.budgetNote(budget));
        return out.build();
    }

    /** Every exact, non-snapshot, remote pin of {@code build}, keyed {@code group:artifact:version}, owned by {@code module}. */
    private static void collect(String module, JkBuild build, Map<String, List<String>> ownersByGav) {
        for (Scope scope : Scope.values()) {
            for (Dependency d : build.dependencies().of(scope)) {
                String version = exactRemoteVersion(d);
                if (version == null) continue;
                ownersByGav
                        .computeIfAbsent(d.module() + ":" + version, k -> new ArrayList<>())
                        .add(module);
            }
        }
    }

    /**
     * The version to check, or {@code null}: a workspace, git, path, file or platform-managed edge
     * is not a repository lookup, a floating selector is the lock's to settle, a snapshot is asked
     * of snapshot repositories under rules of its own, and an unresolved literal has its own row.
     */
    static @Nullable String exactRemoteVersion(Dependency d) {
        if (d.isWorkspace() || d.isGit() || d.isPath() || d.isFile() || d.isPlatformManaged()) return null;
        if (!(d.version() instanceof VersionSelector.Exact exact)) return null;
        String version = exact.version();
        if (version.isBlank() || DependencyMapping.UNRESOLVED.equals(version) || version.endsWith("-SNAPSHOT")) {
            return null;
        }
        return version;
    }

    /**
     * What the sweep found: a verdict per {@code group:artifact:version} it reached, and the pins
     * it did not — those still queued when the budget ran out.
     */
    record Sweep(Map<String, Verdict> verdicts, List<String> unchecked) {

        /** The one Tier-2 note for every pin the budget left unchecked. */
        String budgetNote(Duration budget) {
            Set<String> coordinates = new LinkedHashSet<>();
            for (String gav : unchecked) coordinates.add(gav.substring(0, gav.lastIndexOf(':')));
            return unchecked.size() + (unchecked.size() == 1 ? " pinned version" : " pinned versions") + " ("
                    + coordinates.size() + (coordinates.size() == 1 ? " coordinate" : " coordinates")
                    + ") were not checked against the repositories the lock reads: the check stopped at its "
                    + budget.toSeconds() + "-second budget after " + verdicts.size() + " of "
                    + (verdicts.size() + unchecked.size()) + ". `jk lock` decides whether each version exists.";
        }
    }

    /**
     * One catalog walk per {@code group:artifact} whose versions the stores do not already answer,
     * {@link #walksInFlight()} at a time on the io pool, under the caller's session. When {@code
     * budget} runs out, a walk that has not started is not started and one in flight asks no
     * further repository: the versions neither has an answer for are the sweep's {@link
     * Sweep#unchecked}.
     */
    private static Sweep walkAll(Set<String> gavs, RepoGroup repos, Clock clock, Duration budget) {
        var session = SessionContext.current();
        Semaphore permits = new Semaphore(walksInFlight());
        Refusals refusals = new Refusals();
        long deadline = clock.nanos() + budget.toNanos();
        Map<String, Verdict> out = new LinkedHashMap<>();
        Map<String, Set<String>> toWalk = new LinkedHashMap<>();
        for (String gav : gavs) {
            Coordinate coord = coordinate(gav);
            if (mirrored(coord, repos)) {
                out.put(gav, Verdict.LISTED);
                continue;
            }
            toWalk.computeIfAbsent(coord.module(), k -> new LinkedHashSet<>()).add(coord.version());
        }
        Map<String, Future<@Nullable Walked>> pending = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : toWalk.entrySet()) {
            String ga = e.getKey();
            Set<String> versions = Set.copyOf(e.getValue());
            pending.put(
                    ga,
                    JkThreads.io()
                            .submit(() -> SessionContext.where(session, () -> {
                                permits.acquire();
                                try {
                                    if (clock.nanos() >= deadline) return null;
                                    return walk(ga, versions, repos, refusals, clock, deadline);
                                } finally {
                                    permits.release();
                                }
                            })));
        }
        List<String> unchecked = new ArrayList<>();
        for (Map.Entry<String, Future<@Nullable Walked>> e : pending.entrySet()) {
            Set<String> versions = Objects.requireNonNull(toWalk.get(e.getKey()));
            try {
                @Nullable Walked walked = e.getValue().get();
                for (String version : versions) {
                    String gav = e.getKey() + ":" + version;
                    @Nullable
                    Verdict verdict = walked == null ? null : walked.verdicts().get(version);
                    if (verdict == null) unchecked.add(gav);
                    else out.put(gav, verdict);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                for (Future<@Nullable Walked> f : pending.values()) f.cancel(true);
                return new Sweep(out, List.copyOf(unchecked));
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause() == null ? failed : failed.getCause();
                for (String version : versions) {
                    out.put(
                            e.getKey() + ":" + version,
                            Verdict.unchecked(List.of(), List.of("this check failed: " + describe(cause))));
                }
            }
        }
        return new Sweep(out, List.copyOf(unchecked));
    }

    /** One coordinate's walk: a verdict per version it reached an answer for; the rest were cut off by the budget. */
    private record Walked(Map<String, Verdict> verdicts) {}

    /**
     * The repositories the lock asks for {@code ga}, in the lock's order, each read once until every
     * version in {@code versions} has been listed by one of them or {@code deadline} has passed.
     * A repository that fails to answer is recorded, not skipped — in {@code refusals} for the
     * sweep, so no later walk asks it — and the verdicts of the versions no repository listed
     * carry it. A version still open when the deadline cuts the walk short has no verdict: an
     * unasked repository may list it.
     */
    private static Walked walk(
            String ga, Set<String> versions, RepoGroup repos, Refusals refusals, Clock clock, long deadline)
            throws InterruptedException {
        Coordinate coord = Coordinate.ofModule(ga, versions.iterator().next());
        Map<String, Verdict> out = new LinkedHashMap<>();
        Set<String> remaining = new LinkedHashSet<>(versions);
        List<String> asked = new ArrayList<>();
        Set<String> listed = new LinkedHashSet<>();
        List<String> unreachable = new ArrayList<>();
        for (MavenRepo repo : repos.repositoriesFor(coord)) {
            if (remaining.isEmpty()) break;
            if (!repo.servesReleases()) continue;
            if (clock.nanos() >= deadline) return new Walked(out);
            asked.add(repo.name());
            String refused = refusals.awaitFirstAsk(repo.name(), deadline - clock.nanos());
            if (refused != null) {
                unreachable.add(repo.name() + " was not asked again after it " + refused);
                continue;
            }
            try {
                List<String> catalog = repo.availableVersions(coord);
                for (String version : List.copyOf(remaining)) {
                    if (catalog.contains(version)) {
                        out.put(version, Verdict.LISTED);
                        remaining.remove(version);
                    }
                }
                if (!catalog.isEmpty()) listed.add(repo.name() + " lists " + named(catalog));
            } catch (IOException transport) {
                refusals.record(repo.name(), summary(transport));
                unreachable.add(repo.name() + " could not be reached (" + describe(transport) + ")");
            } finally {
                refusals.asked(repo.name());
            }
        }
        Verdict unlisted = new Verdict(false, List.copyOf(asked), List.copyOf(listed), List.copyOf(unreachable));
        for (String version : remaining) out.put(version, unlisted);
        return new Walked(out);
    }

    /** Whether a release repository the lock asks for {@code coord} holds its POM in the local store, no network read. */
    private static boolean mirrored(Coordinate coord, RepoGroup repos) {
        for (MavenRepo repo : repos.repositoriesFor(coord)) {
            if (repo.servesReleases() && repo.tryLocalPom(coord).isPresent()) return true;
        }
        return false;
    }

    private static Coordinate coordinate(String gav) {
        int cut = gav.lastIndexOf(':');
        return Coordinate.ofModule(gav.substring(0, cut), gav.substring(cut + 1));
    }

    /** The newest {@link #VERSIONS_NAMED} entries of a catalog, newest first, with the count elided. */
    private static String named(List<String> versions) {
        List<String> newestFirst = new ArrayList<>(versions.reversed());
        if (newestFirst.size() <= VERSIONS_NAMED) return String.join(", ", newestFirst);
        return String.join(", ", newestFirst.subList(0, VERSIONS_NAMED)) + " and "
                + (newestFirst.size() - VERSIONS_NAMED) + " older";
    }

    /**
     * A failure in a few words, for the rows of the walks that did not ask again: the HTTP status a
     * request drew, a rate limit, an address nothing answers at; anything else as {@link #describe}.
     */
    static String summary(IOException failure) {
        String message = failure.getMessage() == null ? "" : failure.getMessage();
        if (failure instanceof RateLimitedException) return "was rate-limited";
        if (failure instanceof MavenRepo.RepositoryUnreachableException) return "could not be reached";
        Matcher status = HTTP_STATUS.matcher(message);
        if (status.find()) return "answered HTTP " + status.group(1);
        return "failed (" + describe(failure) + ")";
    }

    private static final Pattern HTTP_STATUS = Pattern.compile("^HTTP (\\d{3}) ");

    private static String describe(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String head =
                failure.getClass().getSimpleName() + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
        return root == failure ? head : head + " — " + root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    /**
     * One sweep's memory of the repositories that failed to answer, by name. The first walk to
     * reach a repository asks it while the others wait for that one answer; once it has failed —
     * at the first ask or any later one — no walk of this sweep asks it again.
     */
    static final class Refusals {

        private final Map<String, String> failures = new ConcurrentHashMap<>();
        private final Map<String, CompletableFuture<Void>> firstAsks = new ConcurrentHashMap<>();

        /**
         * The recorded failure of {@code repository}, or null once it may be asked: the first
         * caller for a repository is told to ask at once, every other waits — for {@code
         * remainingNanos} at most — until that first ask has an outcome.
         */
        @Nullable
        String awaitFirstAsk(String repository, long remainingNanos) throws InterruptedException {
            String failed = failures.get(repository);
            if (failed != null) return failed;
            CompletableFuture<Void> mine = new CompletableFuture<>();
            CompletableFuture<Void> first = firstAsks.putIfAbsent(repository, mine);
            if (first == null) return null;
            try {
                first.get(Math.max(0, remainingNanos), TimeUnit.NANOSECONDS);
            } catch (TimeoutException | ExecutionException outcomeUnknown) {
                // The first ask is still open or died before answering: ask, and judge for oneself.
            }
            return failures.get(repository);
        }

        /** {@code repository} failed to answer: {@code why} completes "was not asked again after it …" in every later walk's row. */
        void record(String repository, String why) {
            failures.putIfAbsent(repository, why);
        }

        /** The first ask of {@code repository} is over, whatever it answered. */
        void asked(String repository) {
            CompletableFuture<Void> first = firstAsks.get(repository);
            if (first != null) first.complete(null);
        }
    }

    /**
     * What the walk found for one pin: listed somewhere, or not, with the repositories asked,
     * what those that answered list instead, and the ones that did not answer.
     */
    record Verdict(boolean listed, List<String> asked, List<String> catalogs, List<String> unreachable) {

        static final Verdict LISTED = new Verdict(true, List.of(), List.of(), List.of());

        static Verdict unchecked(List<String> asked, List<String> unreachable) {
            return new Verdict(false, asked, List.of(), unreachable);
        }

        /** The row for {@code gav}, a Tier-3 refusal foretold or a Tier-2 note that the check did not run. */
        String render(String gav) {
            int cut = gav.lastIndexOf(':');
            String module = gav.substring(0, cut);
            String version = gav.substring(cut + 1);
            String pin = "`" + module + " " + version + "`";
            if (!unreachable.isEmpty()) {
                return pin + " is pinned by the POM and was not checked against the repositories the lock reads: "
                        + String.join("; ", unreachable) + ". `jk lock` decides whether the version exists.";
            }
            String where = catalogs.isEmpty()
                    ? "no repository the lock reads (" + String.join(", ", asked) + ") lists " + module + " at all"
                    : "no repository the lock reads lists that version (" + String.join("; ", catalogs) + ")";
            return pin + " is pinned by the POM, and " + where + "; `jk lock` refuses it. Pin a version a repository"
                    + " lists, or declare the repository that publishes " + version + " in `[repositories]`.";
        }
    }
}
