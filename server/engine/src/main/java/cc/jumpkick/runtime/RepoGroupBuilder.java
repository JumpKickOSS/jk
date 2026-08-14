// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.ObjectStoreConfig;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.repo.ExclusiveGroups;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoCredentialResolver;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.repo.RepoTransport;
import cc.jumpkick.repo.RepoTransports;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Builds a {@link RepoGroup} from project/global repositories (project wins on name clash),
 * optional test {@code --repo-url} override, else the public baseline: JumpKick first-party
 * (exclusive {@code cc.jumpkick.*} / {@code build.jumpkick.*}), then Maven Central, then Google
 * Maven. Shares one {@link Http} and {@link Cas} across the resulting repos.
 *
 * <p>Remote selection by groupId:
 *
 * <ul>
 * <li>{@code cc.jumpkick} / {@code build.jumpkick} (and subpackages) → JumpKick only
 * <li>everything else → Central, then Google (JumpKick is not probed)
 * </ul>
 *
 * <p>Resolve order (logical): local materialization (CAS, {@code repos/*}, {@code ~/.m2}) then
 * remotes among the eligible set above.
 */
public final class RepoGroupBuilder {

    /**
     * Built-in remotes when the project declares none (and always appended if missing). JumpKick
     * is first so exclusive groups take effect before Central can confuse first-party coords.
     */
    static final List<RepositorySpec> DEFAULT_REMOTE_REPOS =
            List.of(RepositorySpec.JUMPKICK, RepositorySpec.MAVEN_CENTRAL, RepositorySpec.GOOGLE_MAVEN);

    private RepoGroupBuilder() {}

    /**
     * Expand {@code ${VAR}} in object-store credentials, strictly: an unset variable is an error
     * rather than a silent null that would fall through to the ambient AWS chain and fail far away
     * from the cause.
     */
    private static ObjectStoreConfig expandObjectStore(
            String repoName, ObjectStoreConfig cfg, UnaryOperator<String> env) {
        if (cfg == null || cfg.isEmpty()) return ObjectStoreConfig.EMPTY;
        return new ObjectStoreConfig(
                interp(repoName, cfg.region(), env),
                interp(repoName, cfg.endpoint(), env),
                interp(repoName, cfg.accessKey(), env),
                interp(repoName, cfg.secretKey(), env),
                interp(repoName, cfg.sessionToken(), env));
    }

    private static String interp(String repoName, String raw, UnaryOperator<String> env) {
        return cc.jumpkick.config.RepositoryToml.interpolate(raw, var -> {
            String value = env.apply(var);
            if (value == null) {
                throw new IllegalStateException(
                        "repositories." + repoName + " references unset environment variable ${" + var + "}");
            }
            return value;
        });
    }

    public static RepoGroup buildFor(JkBuild project, URI overrideUrl, Cas cas) {
        return buildFor(project, overrideUrl, cas, System::getenv);
    }

    /**
     * As {@link #buildFor(JkBuild, URI, Cas)} but resolving credentials against {@code env}
     *
     *
     * <p>Inline {@code ${VAR}} credentials are expanded here rather than during the parse, so this
     * is where the request's environment has to arrive. Build-path callers pass
     * {@code Inputs.env}, which layers the project's {@code.env} under the caller's shell
     * environment; the three-argument overload keeps ambient behaviour for tooling and tests.
     */
    public static RepoGroup buildFor(JkBuild project, URI overrideUrl, Cas cas, UnaryOperator<String> env) {
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
            RepoCredentialResolver creds = RepoCredentialResolver.withEnv(env::apply);
            List<List<String>> exclusiveGroups = new ArrayList<>(effective.size());
            for (RepositorySpec spec : effective) {
                RepoCredential cred = creds.resolve(spec.name(), spec.url(), spec.credential());
                // Per-repo object-store config (region/endpoint/keys) flows to the
                // transport; HTTP credentials still ride the MavenRepo credential.
                // Object-store keys carry raw ${VAR} out of the parse for the same reason
                // credentials dothey are secrets, so they must not be committed
                // literally, and expansion belongs where the request's environment is in scope.
                RepoTransport transport = RepoTransports.forUrl(
                        spec.url(),
                        http,
                        expandObjectStore(spec.name(), spec.objectStore().orElse(ObjectStoreConfig.EMPTY), env));
                // Hand the client through, not just the transport: the transport-only constructor nulls it,
                // which silently disabled the metadata TTL cache and the ~/.m2 probe for every real
                // build.
                repos.add(MavenRepo.overTransport(spec.name(), spec.url(), transport, cas, cred, http, mirrorToM2));
                exclusiveGroups.add(exclusiveGroupsFor(spec));
            }
            maybeWarnMultiRepoWithoutBindings(effective, exclusiveGroups);
            return new RepoGroup(repos, exclusiveGroups);
        }
        return new RepoGroup(repos);
    }

    /**
     * Exclusive patterns for {@code spec}. Google Android Maven always carries
     * {@link RepositorySpec#GOOGLE_ANDROID_EXCLUSIVE_GROUPS} so {@code androidx.*} never
     * double-probes Central; user-declared {@code groups} on that remote are <em>additive</em> —
     * replacing the built-in list would silently re-open the AndroidX namespace to other repos
     * the moment a user binds one extra group. Elsewhere, declared groups stand alone.
     */
    static List<String> exclusiveGroupsFor(RepositorySpec spec) {
        if (isGoogleAndroidMaven(spec)) {
            if (!spec.hasExclusiveGroups()) return RepositorySpec.GOOGLE_ANDROID_EXCLUSIVE_GROUPS;
            var merged = new LinkedHashSet<>(RepositorySpec.GOOGLE_ANDROID_EXCLUSIVE_GROUPS);
            merged.addAll(spec.groups());
            return List.copyOf(merged);
        }
        if (spec.hasExclusiveGroups()) return spec.groups();
        return List.of();
    }

    /** True for the built-in Google Maven remote (name or well-known host). */
    static boolean isGoogleAndroidMaven(RepositorySpec spec) {
        if (spec == null) return false;
        if ("google".equalsIgnoreCase(spec.name())) return true;
        String host = spec.url().getHost();
        return host != null && (host.equalsIgnoreCase("dl.google.com") || host.equalsIgnoreCase("maven.google.com"));
    }

    /**
     * Once per {@link #buildFor} when the effective remote list has more than one repo and none
     * end up with exclusive bindings (after Google defaults). Soft warn — resolve still proceeds.
     */
    static void maybeWarnMultiRepoWithoutBindings(List<RepositorySpec> effective, List<List<String>> exclusive) {
        if (effective == null || effective.size() <= 1) return;
        if (ExclusiveGroups.anyBinding(exclusive)) return;
        System.err.println("jk: warning: multiple repositories configured without exclusive `groups` bindings "
                + "(dependency-confusion risk). Bind internal namespaces, e.g. "
                + "[repositories.internal] groups = [\"com.acme\", \"com.acme.*\"]. "
                + "Google Android groups are bound by default when the Google Maven remote is present. "
                + "See the guide § Auth and repositories.");
    }

    /**
     * Project/global specs first (insertion order); ensure JumpKick + Central + Google are present
     * (JumpKick always first among the built-ins when we prepend missing first-party exclusive).
     */
    static List<RepositorySpec> effectiveRepos(Map<String, RepositorySpec> byName) {
        if (byName.isEmpty()) {
            return DEFAULT_REMOTE_REPOS;
        }
        List<RepositorySpec> effective = new ArrayList<>();
        // Prefer an explicit project "jumpkick" entry; otherwise prepend the official one so
        // exclusive first-party groups always bind before Central.
        if (byName.containsKey(RepositorySpec.JUMPKICK.name())) {
            effective.add(byName.get(RepositorySpec.JUMPKICK.name()));
        } else {
            effective.add(RepositorySpec.JUMPKICK);
        }
        for (RepositorySpec s : byName.values()) {
            if (!RepositorySpec.JUMPKICK.name().equals(s.name())) {
                effective.add(s);
            }
        }
        for (RepositorySpec builtin : DEFAULT_REMOTE_REPOS) {
            if (!byName.containsKey(builtin.name())
                    && !RepositorySpec.JUMPKICK.name().equals(builtin.name())) {
                effective.add(builtin);
            }
        }
        return effective;
    }
}
