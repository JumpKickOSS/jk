// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.tool;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.jdk.HostPlatform;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.model.ToolCoordSpec;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolver.NaiveResolver;
import cc.jumpkick.resolver.Resolution;
import cc.jumpkick.resolver.Resolver;
import cc.jumpkick.resolver.VersionSelectors;
import cc.jumpkick.resolver.Versions;
import cc.jumpkick.resolver.pubgrub.VersionSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves a Maven coord into a {@link ToolEnv}: walk POM, fetch jars into {@link Cas}, read
 * Main-Class (caller override wins).
 */
public final class ToolResolver {

    private final RepoGroup repos;

    public ToolResolver(RepoGroup repos) {
        this.repos = Objects.requireNonNull(repos, "repos");
    }

    /** Convenience: Central-only resolver backed by a shared {@link Cas}. */
    public static ToolResolver mavenCentral(Http http, Cas cas) {
        MavenRepo central =
                new MavenRepo(RepositorySpec.MAVEN_CENTRAL.name(), RepositorySpec.MAVEN_CENTRAL.url(), http, cas);
        return new ToolResolver(RepoGroup.of(central));
    }

    /**
     * Resolve {@code spec} (floating specs pinned first). {@code withSpecs} are extra root deps
     * merged into the classpath.
     */
    public ToolEnv resolve(ToolCoordSpec spec, String binName, String mainClassOverride, List<ToolCoordSpec> withSpecs)
            throws IOException, InterruptedException {
        Coordinate primary = pin(spec);
        List<Dependency> extras = new ArrayList<>();
        for (ToolCoordSpec w : withSpecs) {
            Coordinate c = pin(w);
            extras.add(new Dependency(c.module(), VersionSelector.parse("=" + c.version())));
        }
        return resolve(primary, binName, mainClassOverride, extras);
    }

    private Coordinate pin(ToolCoordSpec spec) throws IOException, InterruptedException {
        return switch (spec) {
            case ToolCoordSpec.Pinned p -> p.coordinate();
            case ToolCoordSpec.Floating f ->
                Coordinate.of(f.group(), f.artifact(), pickVersion(f.module(), f.selector()));
        };
    }

    public ToolEnv resolve(Coordinate primary, String binName, String mainClassOverride)
            throws IOException, InterruptedException {
        return resolve(primary, binName, mainClassOverride, List.of());
    }

    public ToolEnv resolve(Coordinate primary, String binName, String mainClassOverride, List<Dependency> extras)
            throws IOException, InterruptedException {
        Objects.requireNonNull(primary, "primary");
        Objects.requireNonNull(binName, "binName");
        Objects.requireNonNull(extras, "extras");

        // Published native binary for this platform beats the JVM path unless Main-Class is pinned.
        if (mainClassOverride == null || mainClassOverride.isBlank()) {
            var nativeBinary = fetchNativeBinary(primary);
            if (nativeBinary.isPresent()) {
                Path bin = nativeBinary.get();
                //noinspection ResultOfMethodCallIgnored — best-effort; exec fails loudly if it didn't stick
                bin.toFile().setExecutable(true, false);
                return new ToolEnv(binName, primary, ToolEnv.NATIVE_BINARY, List.of(bin));
            }
        }

        // 1. Transitive resolution from the primary coord (+ any --with extras).
        Resolver resolver = new NaiveResolver(new EffectivePomBuilder(repos));
        Dependency root = new Dependency(
                primary.group() + ":" + primary.artifact(), VersionSelector.parse("=" + primary.version()));
        List<Dependency> roots = new ArrayList<>();
        roots.add(root);
        roots.addAll(extras);
        Resolution resolution = resolver.resolve(roots);

        // 2. Fetch each resolved jar. Primary first so classpath order is stable.
        Path primaryJar = fetchJar(primary);
        List<Path> classpath = new ArrayList<>();
        classpath.add(primaryJar);
        String primaryKey = primary.group() + ":" + primary.artifact();
        for (Resolution.ResolvedModule m : resolution.modules().values()) {
            if (m.module().equals(primaryKey)) continue;
            classpath.add(fetchJar(m.coordinate()));
        }

        // 3. Main-Class detection.
        String mainClass = mainClassOverride;
        if (mainClass == null || mainClass.isBlank()) {
            Optional<String> fromManifest = JarManifest.mainClass(primaryJar);
            mainClass = fromManifest.orElseThrow(
                    () -> new IOException(primary + " has no Main-Class in its manifest — pass --main <class>."));
        }
        return new ToolEnv(binName, primary, mainClass, classpath);
    }

    /**
     * Pin a floating selector against the union of versions the repos advertise (maven-metadata,
     * TTL-cached). Highest match wins; {@code latest} considers {@linkplain Versions#isStable
     * stable} releases only, falling back to the overall highest when nothing stable exists.
     */
    private String pickVersion(String module, VersionSelector selector) throws IOException, InterruptedException {
        if (selector instanceof VersionSelector.Exact e) return e.version();
        List<String> available = repos.availableVersions(Coordinate.ofModule(module, "any"));
        if (available.isEmpty()) {
            throw new MavenRepo.ArtifactNotFoundException("no versions of " + module + " found in any declared repo");
        }
        VersionSet set = VersionSelectors.toVersionSet(selector);
        List<String> matching = available.stream().filter(set::contains).toList();
        if (selector instanceof VersionSelector.Latest) {
            List<String> stable = matching.stream().filter(Versions::isStable).toList();
            if (!stable.isEmpty()) matching = stable;
        }
        return matching.stream()
                .max(Versions::compare)
                .orElseThrow(() -> new MavenRepo.ArtifactNotFoundException("no version of " + module + " matches "
                        + selector.raw() + " (available: " + String.join(", ", available) + ")"));
    }

    /**
     * Platform-native binary for {@code primary}: {@code native-<arch>-<os>} then protoc-style
     * {@code <os>-<arch>} classifiers (type {@code exe}). Empty → fall through to jar path.
     */
    private Optional<Path> fetchNativeBinary(Coordinate primary) throws IOException, InterruptedException {
        String os = HostPlatform.currentOs();
        String arch = HostPlatform.currentArch();
        if (HostPlatform.UNSUPPORTED.equals(os) || HostPlatform.UNSUPPORTED.equals(arch)) {
            return Optional.empty();
        }
        String protocOs = "macos".equals(os) ? "osx" : os;
        String protocArch = "aarch64".equals(arch) ? "aarch_64" : arch;
        List<String> classifiers = List.of("native-" + arch + "-" + os, protocOs + "-" + protocArch);
        for (String classifier : classifiers) {
            var fetched = repos.tryFetchArtifact(
                    new Coordinate(primary.group(), primary.artifact(), primary.version(), classifier, "exe"));
            if (fetched.isPresent()) {
                return Optional.of(fetched.get().fetched().cachePath());
            }
        }
        return Optional.empty();
    }

    private Path fetchJar(Coordinate coord) throws IOException, InterruptedException {
        return repos.tryFetchArtifact(coord)
                .orElseThrow(
                        () -> new MavenRepo.ArtifactNotFoundException("jar not found in any declared repo: " + coord))
                .fetched()
                .cachePath();
    }
}
