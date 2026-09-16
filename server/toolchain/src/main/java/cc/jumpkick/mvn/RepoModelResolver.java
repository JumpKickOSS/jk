// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.PomParseException;
import cc.jumpkick.repo.PomParser;
import cc.jumpkick.repo.RepoGroup;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.FileModelSource;
import org.apache.maven.model.building.ModelSource;
import org.apache.maven.model.resolution.InvalidRepositoryException;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.UnresolvableModelException;

/**
 * Maven's parent / BOM lookup routed through jk's repository client: the caller's {@link
 * RepoGroup} (global repositories over the public baseline, the {@code ~/.m2} probe and the store
 * included), with every {@code <repository>} the POM under import declares consulted first. A
 * fetched POM passes through jk's hardened XML parser before Maven reads it.
 */
// ModelResolver's own signatures name the ModelSource type Maven 3.9 deprecates.
@SuppressWarnings("deprecation")
final class RepoModelResolver implements ModelResolver {

    private RepoGroup repos;
    private final Http http;
    private final Cas cas;
    private final Set<String> declared;

    RepoModelResolver(RepoGroup repos, Cas cas) {
        this(repos, new Http(), cas, new HashSet<>());
    }

    private RepoModelResolver(RepoGroup repos, Http http, Cas cas, Set<String> declared) {
        this.repos = repos;
        this.http = http;
        this.cas = cas;
        this.declared = declared;
    }

    /** The group this resolver was built over: what the lock reads before a POM's own {@code <repository>} entries. */
    RepoGroup repos() {
        return repos;
    }

    Cas cas() {
        return cas;
    }

    @Override
    public ModelSource resolveModel(String groupId, String artifactId, String version)
            throws UnresolvableModelException {
        Coordinate coord = Coordinate.of(groupId, artifactId, version);
        try {
            Optional<RepoGroup.RepoFetched> hit = repos.tryFetchPom(coord);
            if (hit.isEmpty()) {
                throw new UnresolvableModelException(
                        "no repository has " + coord.toGav() + " (asked " + repoNames() + ")",
                        groupId,
                        artifactId,
                        version);
            }
            Path pom = hit.get().fetched().cachePath();
            PomParser.parseXml(Files.readAllBytes(pom));
            return new FileModelSource(pom.toFile());
        } catch (IOException | PomParseException | IllegalArgumentException e) {
            // IllegalArgumentException: a coordinate still carrying a ${placeholder} is not a path.
            throw new UnresolvableModelException(
                    coord.toGav() + ": " + e.getMessage(), groupId, artifactId, version, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UnresolvableModelException(
                    "interrupted fetching " + coord.toGav(), groupId, artifactId, version, e);
        }
    }

    @Override
    public ModelSource resolveModel(Parent parent) throws UnresolvableModelException {
        return resolveModel(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
    }

    @Override
    public ModelSource resolveModel(Dependency dependency) throws UnresolvableModelException {
        return resolveModel(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion());
    }

    @Override
    public void addRepository(Repository repository) throws InvalidRepositoryException {
        addRepository(repository, false);
    }

    /** A {@code <repository>} of the POM under import; Central is already in the group. */
    @Override
    public void addRepository(Repository repository, boolean replace) throws InvalidRepositoryException {
        String url = repository.getUrl();
        if (url == null || url.isBlank() || isCentral(url)) return;
        String id = repository.getId() == null || repository.getId().isBlank() ? url : repository.getId();
        if (!declared.add(id)) return;
        try {
            URI uri = new URI(url.trim());
            if (repos.repos().stream().anyMatch(r -> r.baseUrl().equals(uri))) return;
            repos = repos.withReposPrepended(List.of(new MavenRepo(id, uri, http, cas)));
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new InvalidRepositoryException(e.getMessage(), repository, e);
        }
    }

    @Override
    public ModelResolver newCopy() {
        return new RepoModelResolver(repos, http, cas, new HashSet<>(declared));
    }

    private String repoNames() {
        return repos.repos().stream().map(MavenRepo::name).collect(Collectors.joining(", "));
    }

    static boolean isCentral(String url) {
        return url.startsWith(RepositorySpec.MAVEN_CENTRAL.url().toString())
                || url.startsWith("https://repo.maven.apache.org/")
                || url.startsWith("https://repo1.maven.org/");
    }
}
