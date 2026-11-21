// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.http.Http;
import cc.jumpkick.http.SafeUri;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.PackageId;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.repo.EffectivePom;
import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.Pom;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.repo.RepoMirrors;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The repositories dependency POMs declare, scoped the way Maven scopes them: a {@code
 * <repository>} in a POM (or its parents) is consulted for that POM's dependencies and theirs, after
 * every repository the project declares, and never for the project's own declarations or another
 * subtree. A package reached through such a POM is <em>granted</em> the repository; its versions,
 * POM and artifact are then asked of the project's group with the grant appended.
 *
 * <p>Trust is the project-declared rule with no way to opt out, and narrower: only an {@code
 * https://} repository on a named host is used. A plaintext {@code http://} one, a {@code file:}
 * tree or any other scheme is not, nor is a host on this machine unless the project itself resolves
 * through one, nor a host whose address is internal (private, link-local, unique-local, shared or
 * unspecified) unless the project already resolves through that host; the lock says so. The
 * address is asked again before every request and redirect hop, so a name that resolves inward later,
 * or a redirect onto such a host, puts the repository out of the lock with a warning. A mirror
 * that stands in for the repository is asked in its place, so its address is not checked. An
 * artifact without a published checksum fails the lock naming the repository, as it would for a
 * {@code [repositories]} entry without {@code allow-unverified}. A row a declared repository serves records it in {@code source}, and every
 * repository that served a row is named once in a lock note with the POM that introduced it; a
 * repository merely declared earns none. Under {@code [resolve] pom-repositories = "ignore"} ({@link
 * #ignoreAll}) nothing is granted, and each repository a POM with dependencies declares is named once
 * in a note as not used.
 */
final class DeclaredRepositories {

    /** One group with the grant appended and the POM builder over it. */
    private record Scoped(RepoGroup repos, EffectivePomBuilder builder) {}

    /** The addresses a host name resolves to; {@link InetAddress#getAllByName} outside tests. */
    @FunctionalInterface
    interface HostAddresses {
        InetAddress[] of(String host) throws UnknownHostException;
    }

    private final RepoGroup base;
    private final EffectivePomBuilder baseBuilder;

    /** Whether the project itself resolves through this machine, so a POM may name a loopback repository too. */
    private final boolean projectOnLoopback;

    /** The hosts the project's repositories and mirrors name, lower-cased: a POM may send the resolve there. */
    private final Set<String> projectHosts;

    private final HostAddresses addresses;

    /** {@code [resolve] pom-repositories = "ignore"}: no POM's repository is granted to anything. */
    private volatile boolean ignored;

    /** {@code group:artifact} → the repositories granted to it, in the order they were granted. */
    private final ConcurrentHashMap<String, Set<Pom.Repository>> granted = new ConcurrentHashMap<>();

    /** Grant sets → the group and builder serving them, keyed by their URLs joined. */
    private final ConcurrentHashMap<String, Scoped> scoped = new ConcurrentHashMap<>();

    /** URL → the repository built for it, or absent when its URL is refused. */
    private final ConcurrentHashMap<String, MavenRepo> built = new ConcurrentHashMap<>();

    private final Set<String> refusedUrls = ConcurrentHashMap.newKeySet();
    private final Set<String> notes = ConcurrentHashMap.newKeySet();

    /** URL → the declaration that first granted it, for the note a served row earns. */
    private final ConcurrentHashMap<String, Pom.Repository> declared = new ConcurrentHashMap<>();

    DeclaredRepositories(RepoGroup base, EffectivePomBuilder baseBuilder) {
        this(base, baseBuilder, InetAddress::getAllByName);
    }

    DeclaredRepositories(RepoGroup base, EffectivePomBuilder baseBuilder, HostAddresses addresses) {
        this.base = base;
        this.baseBuilder = baseBuilder;
        this.projectOnLoopback = onLoopback(base);
        this.projectHosts = hosts(base);
        this.addresses = addresses;
    }

    /**
     * Grant the repositories the POM of {@code pkg} makes available — those granted to {@code pkg}
     * itself plus the POM's own — to every package in {@code children}. Returns the {@code
     * group:artifact} keys whose grant grew, so the caller can drop what it cached for them.
     */
    Set<String> propagate(String pkg, EffectivePom pom, List<String> children) {
        if (ignored) {
            if (children.isEmpty()) return Set.of();
            for (Pom.Repository repository : pom.repositories()) {
                notes.add("repository `" + repository.id() + "` (" + display(repository.url()) + ") from the POM of "
                        + repository.declaredBy() + " not used: [resolve] pom-repositories = \"ignore\"");
            }
            return Set.of();
        }
        Set<Pom.Repository> inherited = granted.getOrDefault(ga(pkg), Set.of());
        if (inherited.isEmpty() && pom.repositories().isEmpty()) return Set.of();
        LinkedHashSet<Pom.Repository> available = new LinkedHashSet<>(inherited);
        available.addAll(pom.repositories());
        Set<String> grew = new LinkedHashSet<>();
        for (String child : children) {
            String key = ga(child);
            Set<Pom.Repository> before = granted.get(key);
            Set<Pom.Repository> merged = granted.merge(key, Set.copyOf(available), (a, b) -> {
                LinkedHashSet<Pom.Repository> union = new LinkedHashSet<>(a);
                union.addAll(b);
                return Set.copyOf(union);
            });
            if (before == null || merged.size() != before.size()) grew.add(key);
        }
        if (!children.isEmpty()) {
            for (Pom.Repository repository : pom.repositories()) declared.putIfAbsent(repository.url(), repository);
        }
        return grew;
    }

    /** Grant nothing from here on: the project resolves through its own repositories alone. */
    void ignoreAll() {
        ignored = true;
    }

    /** The group a package's versions, POM and artifact are asked of. */
    RepoGroup reposFor(String pkg) {
        return scopedFor(pkg).repos();
    }

    /** The POM builder over {@link #reposFor}. */
    EffectivePomBuilder builderFor(String pkg) {
        return scopedFor(pkg).builder();
    }

    /**
     * One line per declared repository a row of {@code rows} came from, one per repository refused,
     * and each checksum note a declared repository raised, sorted.
     */
    List<String> notes(Collection<Lockfile.Artifact> rows) {
        Set<String> out = new LinkedHashSet<>(notes);
        for (Lockfile.Artifact row : rows) {
            int plus = row.source().indexOf('+');
            Pom.Repository served = plus < 0 ? null : declared.get(row.source().substring(plus + 1));
            if (served != null && built.containsKey(served.url())) out.add(consulted(served));
        }
        for (MavenRepo repo : built.values()) out.addAll(repo.checksumNotes());
        List<String> sorted = new ArrayList<>(out);
        sorted.sort(null);
        return List.copyOf(sorted);
    }

    private Scoped scopedFor(String pkg) {
        Set<Pom.Repository> grant = granted.get(ga(pkg));
        if (grant == null || grant.isEmpty()) return new Scoped(base, baseBuilder);
        List<MavenRepo> extra = new ArrayList<>();
        for (Pom.Repository repository : grant) {
            MavenRepo repo = repoFor(repository);
            if (repo != null) extra.add(repo);
        }
        if (extra.isEmpty()) return new Scoped(base, baseBuilder);
        String key = extra.stream().map(r -> r.baseUrl().toString()).collect(Collectors.joining("\n"));
        return scoped.computeIfAbsent(key, k -> {
            RepoGroup group = base.withReposAppended(extra);
            return new Scoped(group, new EffectivePomBuilder(group));
        });
    }

    private @Nullable MavenRepo repoFor(Pom.Repository repository) {
        MavenRepo hit = built.get(repository.url());
        if (hit != null) return hit;
        if (refusedUrls.contains(repository.url())) return null;
        URI url;
        try {
            url = new URI(repository.url());
        } catch (URISyntaxException e) {
            refuse(repository, "invalid URL");
            return null;
        }
        String why = refusal(url);
        if (why != null) {
            refuse(repository, why);
            return null;
        }
        MavenRepo template = base.repos().getFirst();
        MavenRepo repo;
        if (RepoMirrors.urlFor(repository.id(), url).isPresent()) {
            repo = RepoMirrors.apply(template.declaredByPom(
                    repository.id(), url, repository.releases(), repository.snapshots(), Http.AddressGuard.ANY));
        } else {
            String internal = addressRefusal(url.getHost());
            if (internal != null) {
                refuse(repository, internal);
                return null;
            }
            // Asked again per request: a name may resolve elsewhere once the first answer expires.
            repo = template.declaredByPom(
                    repository.id(), url, repository.releases(), repository.snapshots(), this::addressRefusal);
        }
        MavenRepo raced = built.putIfAbsent(repository.url(), repo);
        return raced != null ? raced : repo;
    }

    /**
     * Why a POM may not send the resolve to {@code host} by the addresses it resolves to, or null
     * when it may. Every address counts, since the client may connect to any of them. A name that
     * does not resolve is let through: no request reaches it either.
     */
    private @Nullable String addressRefusal(String host) {
        if (projectHosts.contains(host.toLowerCase(Locale.ROOT))) return null;
        InetAddress[] resolved;
        try {
            resolved = addresses.of(host);
        } catch (UnknownHostException | SecurityException unresolved) {
            return null;
        }
        for (InetAddress address : resolved) {
            if (address.isLoopbackAddress()) {
                if (projectOnLoopback) continue;
                return "it resolves to this machine (" + address.getHostAddress()
                        + "); declare it in [repositories] to use it";
            }
            if (internal(address)) {
                return "it resolves to an internal address (" + address.getHostAddress()
                        + "); declare it in [repositories] to use it";
            }
        }
        return null;
    }

    /**
     * Whether {@code address} is one no public repository has: unspecified or {@code 0.0.0.0/8},
     * private, shared ({@code 100.64.0.0/10}), link-local (a cloud's metadata service among them),
     * unique-local ({@code fc00::/7}), site-local or multicast.
     */
    private static boolean internal(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) return true;
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = b[0] & 0xff;
            return first == 0 || (first == 100 && (b[1] & 0xc0) == 64);
        }
        return address instanceof Inet6Address && (b[0] & 0xfe) == 0xfc;
    }

    /**
     * Why a POM may not send the resolve to {@code url} by its spelling, or null when it may. Only
     * {@code https} to a named host is asked: a {@code file:} tree is this machine's disk, and a
     * loopback address is this machine's services, unless the project itself resolves through one.
     * {@link #addressRefusal} then reads what the host resolves to.
     */
    private @Nullable String refusal(URI url) {
        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            return scheme.isEmpty()
                    ? "its URL has no scheme"
                    : "a POM may name only an https repository, not " + scheme;
        }
        if (url.getHost() == null) return "its URL has no host";
        if (RepositorySpec.loopback(url.getHost())) {
            return projectOnLoopback ? null : "it names this machine; declare it in [repositories] to use it";
        }
        if (scheme.equals("http")) return "plain http; add it to [repositories] with allow-insecure = true to use it";
        return null;
    }

    /** The hosts of the repositories the project resolves through and of the mirrors they are asked through. */
    private static Set<String> hosts(RepoGroup group) {
        Set<String> out = new LinkedHashSet<>();
        for (MavenRepo repo : group.repos()) {
            String host = repo.baseUrl().getHost();
            if (host != null) out.add(host.toLowerCase(Locale.ROOT));
            repo.mirror().map(m -> m.url().getHost()).ifPresent(h -> out.add(h.toLowerCase(Locale.ROOT)));
        }
        return Set.copyOf(out);
    }

    /** Whether a repository the project resolves through, or the mirror one is asked through, is on loopback. */
    private static boolean onLoopback(RepoGroup group) {
        for (MavenRepo repo : group.repos()) {
            if (RepositorySpec.loopback(repo.baseUrl().getHost())) return true;
            if (repo.mirror()
                    .map(m -> RepositorySpec.loopback(m.url().getHost()))
                    .orElse(false)) return true;
        }
        return false;
    }

    private void refuse(Pom.Repository repository, String why) {
        refusedUrls.add(repository.url());
        notes.add("repository `" + repository.id() + "` (" + display(repository.url()) + ") from the POM of "
                + repository.declaredBy() + " not used: " + why);
    }

    /** {@code url} as {@link SafeUri#forMessage} prints it; a string that is no URI, as written. */
    private static String display(String url) {
        try {
            return SafeUri.forMessage(new URI(url));
        } catch (URISyntaxException notAUri) {
            return url;
        }
    }

    /** The note a repository earns once a row came from it, however many packages inherit it. */
    private static String consulted(Pom.Repository repository) {
        String policy = repository.releases() && repository.snapshots()
                ? ""
                : repository.snapshots() ? ", snapshots only" : ", releases only";
        return "repository `" + repository.id() + "` (" + display(repository.url()) + ") from the POM of "
                + repository.declaredBy() + " is used for its dependencies after [repositories]" + policy;
    }

    private static String ga(String pkg) {
        return PackageId.isMavenPackageKey(pkg) ? PackageId.parse(pkg).ga() : pkg;
    }

    /** URL → the declaration that first granted a repository, with the policy its POM wrote. */
    Map<String, Pom.Repository> byUrl() {
        return Map.copyOf(declared);
    }

    /** Package-visible for tests: the repositories granted to {@code pkg}. */
    Map<String, Set<Pom.Repository>> grants() {
        return Map.copyOf(granted);
    }
}
