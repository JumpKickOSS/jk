// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.ObjectStoreConfig;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoCredentialResolver;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.repo.RepoTransport;
import cc.jumpkick.repo.RepoTransports;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a {@link RepoGroup} from project/global repositories (project wins on name clash),
 * optional test {@code --repo-url} override, else the public baseline pair Maven Central then
 * Google Maven. Shares one {@link Http} and {@link Cas} across the resulting repos.
 *
 * <p>Resolve order (logical): local materialization (CAS, {@code repos/*}, {@code ~/.m2}) then
 * remotes in declaration order. Built-in defaults are Central first, Google second so R8 /
 * AndroidX coords resolve without a per-project {@code [repositories]} table.
 */
public final class RepoGroupBuilder {

    /** Public baseline when neither project nor global config declares repositories. */
    static final List<RepositorySpec> DEFAULT_REMOTE_REPOS =
            List.of(RepositorySpec.MAVEN_CENTRAL, RepositorySpec.GOOGLE_MAVEN);

    private RepoGroupBuilder() {}

    public static RepoGroup buildFor(JkBuild project, URI overrideUrl, Cas cas) {
        Http http = new Http();
        List<MavenRepo> repos = new ArrayList<>();
        boolean mirrorToM2 = project.project().m2install();
        if (overrideUrl != null) {
            // Tests pin one URL; project-declared repos are ignored.
            repos.add(new MavenRepo("central", overrideUrl, http, cas, RepoCredential.ANONYMOUS, mirrorToM2));
        } else {
            // Merge: project repos > global repos > built-in public baseline.
            // Deduplicate by name: first declaration wins (project beats global,
            // global beats built-in).
            List<RepositorySpec> projectRepos = project.repositories();
            List<RepositorySpec> globalRepos = GlobalConfig.repositories();

            // Build ordered dedup map: project first, then global fill-ins.
            Map<String, RepositorySpec> byName = new LinkedHashMap<>();
            for (RepositorySpec s : projectRepos) byName.put(s.name(), s);
            for (RepositorySpec s : globalRepos) byName.putIfAbsent(s.name(), s);

            List<RepositorySpec> effective = effectiveRepos(byName);

            // Resolve credentials per declared repo (env / store / settings.xml /
            // forge-token bridge). Public repos resolve to ANONYMOUS, so this is
            // transparent for Maven Central, Google Maven, and other open mirrors.
            RepoCredentialResolver creds = new RepoCredentialResolver();
            for (RepositorySpec spec : effective) {
                RepoCredential cred = creds.resolve(spec.name(), spec.url(), spec.credential());
                // Per-repo object-store config (region/endpoint/keys) flows to the
                // transport; HTTP credentials still ride the MavenRepo credential.
                RepoTransport transport = RepoTransports.forUrl(
                        spec.url(), http, spec.objectStore().orElse(ObjectStoreConfig.EMPTY));
                repos.add(new MavenRepo(spec.name(), spec.url(), transport, cas, cred, mirrorToM2));
            }
        }
        return new RepoGroup(repos);
    }

    /**
     * Project/global specs first (insertion order); append each missing built-in default so a
     * corporate-only list still has a public baseline (Central, then Google).
     */
    static List<RepositorySpec> effectiveRepos(Map<String, RepositorySpec> byName) {
        if (byName.isEmpty()) {
            return DEFAULT_REMOTE_REPOS;
        }
        List<RepositorySpec> effective = new ArrayList<>(byName.values());
        for (RepositorySpec builtin : DEFAULT_REMOTE_REPOS) {
            if (!byName.containsKey(builtin.name())) {
                effective.add(builtin);
            }
        }
        return effective;
    }
}
