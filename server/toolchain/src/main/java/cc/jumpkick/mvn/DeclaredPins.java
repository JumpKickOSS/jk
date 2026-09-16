// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.run.JkThreads;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import org.jspecify.annotations.Nullable;

/**
 * The import-time check of every exact pin a POM wrote: a version no repository the lock reads
 * lists is a Tier-3 row here, where the POM is still in front of the user, rather than a refusal
 * at {@code jk lock}. One catalog walk per pinned {@code group:artifact:version}, over the same
 * {@code maven-metadata.xml} the lock reads and in the lock's repository order, stopping at the
 * first repository that lists the version; a repository that cannot be reached makes the pin a
 * note, because an absent answer is not an absent version.
 */
public final class DeclaredPins {

    /** Catalog walks in flight at once: enough to hide latency, few enough to stay under a host's rate limit. */
    private static final int WALKS_IN_FLIGHT = 8;

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
        return check(root, modules, report, lockRepos(base, root, importer.resolver.cas()));
    }

    /**
     * The repositories the lock of {@code imported} reads, in the lock's order: every
     * {@code [repositories]} entry the import wrote onto it, under its release/snapshot policy,
     * ahead of {@code base}.
     */
    static RepoGroup lockRepos(RepoGroup base, JkBuild imported, Cas cas) {
        List<MavenRepo> declared = new ArrayList<>();
        Http http = new Http();
        for (RepositorySpec spec : imported.repositories()) {
            if (base.repos().stream().anyMatch(r -> r.baseUrl().equals(spec.url()))) continue;
            declared.add(
                    new MavenRepo(spec.name(), spec.url(), http, cas).withPolicy(spec.releases(), spec.snapshots()));
        }
        return base.withReposPrepended(declared);
    }

    /** {@link #check(JkBuild, Map, ImportReport, PomImporter)} over exactly {@code repos}. */
    static ImportReport check(JkBuild root, Map<String, JkBuild> modules, ImportReport report, RepoGroup repos) {
        Map<String, List<String>> ownersByGav = new LinkedHashMap<>();
        collect("", root, ownersByGav);
        for (Map.Entry<String, JkBuild> e : modules.entrySet()) collect(e.getKey(), e.getValue(), ownersByGav);
        if (ownersByGav.isEmpty()) return report;
        Map<String, Verdict> verdicts = walkAll(ownersByGav.keySet(), repos);
        ImportReport.Builder out = ImportReport.builder();
        for (ImportReport.Issue issue : report.issues()) {
            if (issue.severity() == ImportReport.Severity.ERROR) out.error(issue.message());
            else out.warning(issue.message());
        }
        ModuleRows rows = new ModuleRows();
        for (Map.Entry<String, List<String>> e : ownersByGav.entrySet()) {
            Verdict verdict = verdicts.get(e.getKey());
            if (verdict == null || verdict.listed()) continue;
            ImportReport.Severity severity =
                    verdict.unreachable().isEmpty() ? ImportReport.Severity.ERROR : ImportReport.Severity.WARNING;
            String row = verdict.render(e.getKey());
            for (String owner : e.getValue()) rows.add(owner, severity, row);
        }
        rows.flush(out);
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

    /** One walk per GAV, {@link #WALKS_IN_FLIGHT} at a time on the io pool, under the caller's session. */
    private static Map<String, Verdict> walkAll(Set<String> gavs, RepoGroup repos) {
        var session = SessionContext.current();
        Semaphore permits = new Semaphore(WALKS_IN_FLIGHT);
        Map<String, Future<Verdict>> pending = new LinkedHashMap<>();
        for (String gav : gavs) {
            pending.put(
                    gav,
                    JkThreads.io()
                            .submit(() -> SessionContext.where(session, () -> {
                                permits.acquire();
                                try {
                                    return walk(gav, repos);
                                } finally {
                                    permits.release();
                                }
                            })));
        }
        Map<String, Verdict> out = new LinkedHashMap<>();
        for (Map.Entry<String, Future<Verdict>> e : pending.entrySet()) {
            try {
                out.put(e.getKey(), e.getValue().get());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                for (Future<Verdict> f : pending.values()) f.cancel(true);
                return out;
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause() == null ? failed : failed.getCause();
                out.put(e.getKey(), Verdict.unchecked(List.of(), List.of("this check failed: " + describe(cause))));
            }
        }
        return out;
    }

    /**
     * The repositories the lock asks for {@code gav}, in the lock's order, each read until one
     * lists the pinned version. A repository that fails to answer is recorded, not skipped.
     */
    private static Verdict walk(String gav, RepoGroup repos) throws InterruptedException {
        int cut = gav.lastIndexOf(':');
        Coordinate coord = Coordinate.ofModule(gav.substring(0, cut), gav.substring(cut + 1));
        List<String> asked = new ArrayList<>();
        Set<String> listed = new LinkedHashSet<>();
        List<String> unreachable = new ArrayList<>();
        for (MavenRepo repo : repos.repositoriesFor(coord)) {
            if (!repo.servesReleases()) continue;
            asked.add(repo.name());
            try {
                List<String> versions = repo.availableVersions(coord);
                if (versions.contains(coord.version())) return Verdict.LISTED;
                if (!versions.isEmpty()) listed.add(repo.name() + " lists " + named(versions));
            } catch (IOException transport) {
                unreachable.add(repo.name() + " could not be reached (" + describe(transport) + ")");
            }
        }
        return new Verdict(false, List.copyOf(asked), List.copyOf(listed), List.copyOf(unreachable));
    }

    /** The newest {@link #VERSIONS_NAMED} entries of a catalog, newest first, with the count elided. */
    private static String named(List<String> versions) {
        List<String> newestFirst = new ArrayList<>(versions.reversed());
        if (newestFirst.size() <= VERSIONS_NAMED) return String.join(", ", newestFirst);
        return String.join(", ", newestFirst.subList(0, VERSIONS_NAMED)) + " and "
                + (newestFirst.size() - VERSIONS_NAMED) + " older";
    }

    private static String describe(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String head =
                failure.getClass().getSimpleName() + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
        return root == failure ? head : head + " — " + root.getClass().getSimpleName() + ": " + root.getMessage();
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
