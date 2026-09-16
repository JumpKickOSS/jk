// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.interop;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.engine.ReleaseArtifacts;
import cc.jumpkick.cli.engine.ReleaseDownloadView;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Classpaths;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.repo.ReleaseVerifier;
import cc.jumpkick.repo.RepoArtifactResolver;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Where the Maven event spy jar ({@code jk-maven-spy-<version>.jar}) lives, and how a release
 * install comes by one. Looked up in this order, first hit wins: the {@code jk.maven-spy.jar}
 * system property; the product library ({@code <home>/lib/}, where the installers put it from a
 * dist); the store's {@code jk-local} shelf ({@code jk install} from a checkout); {@code lib/}
 * beside the running client (the dist layout). A released client that finds none fetches its own
 * version's jar from the release directory into the product library — the same directory, signed
 * manifest and checksum the engine jar is held to — the first time {@code jk mvn} needs it. The
 * lookup is also documented in {@code docs/user/migration.md}.
 */
public final class MavenSpyJar {

    /** Explicit override, for tests and for a checkout that has not been installed. */
    static final String PROPERTY = "jk.maven-spy.jar";

    /** The spy's artifact name; the jar is {@code <name>-<version>.jar}. */
    static final String ARTIFACT = "jk-maven-spy";

    /** Every -D the spy needs: its own presence on Maven's extension path and the events file. */
    static final String EXTENSION_PROPERTY = "maven.ext.class.path";

    static final String EVENTS_PROPERTY = "jk.mvn.events";

    /** The chip the fetch renders under; the spy is Maven's business, not the engine's. */
    static final String CHIP = "Maven";

    /**
     * The release a missing jar is fetched from: the directory root, the keys that sign it, and
     * whether this process may reach for it at all ({@link ReleaseArtifacts#applicable}).
     */
    public record Release(URI base, ReleaseVerifier verifier, boolean fetchable) {}

    private final @Nullable Path override;
    private final String version;
    private final Path lib;
    private final List<Path> fallbacks;
    private final Release release;

    /**
     * @param override the {@link #PROPERTY} path, or null
     * @param version the client's version; the jar is that version's
     * @param lib the product library a fetch lands in and the second place looked
     * @param fallbacks places looked after the library — the shelf, the dist's {@code lib/}
     * @param release where a missing jar is fetched from
     */
    public MavenSpyJar(@Nullable Path override, String version, Path lib, List<Path> fallbacks, Release release) {
        this.override = override;
        this.version = version;
        this.lib = lib;
        this.fallbacks = List.copyOf(fallbacks);
        this.release = release;
    }

    /** This process's spy: the property, the home, the shelf, the dist, and the hosted release. */
    public static MavenSpyJar current() {
        String version = JkVersion.VERSION;
        String jar = ARTIFACT + "-" + version + ".jar";
        List<Path> fallbacks = new ArrayList<>();
        RepoArtifactStore.forStoreId(JkDirs.store(), RepoArtifactResolver.JK_LOCAL)
                .locate("cc/jumpkick/" + ARTIFACT + "/" + version + "/" + jar)
                .ifPresent(fallbacks::add);
        clientDir().ifPresent(dir -> fallbacks.add(dir.resolve("lib").resolve(jar)));
        String property = System.getProperty(PROPERTY);
        Release release = new Release(
                ReleaseArtifacts.releasesBase(),
                ReleaseVerifier.current(GlobalConfig.releaseTrustedKeys()),
                ReleaseArtifacts.applicable(
                        version,
                        ReleaseArtifacts.releasedClient(),
                        SessionContext.current().offline()));
        return new MavenSpyJar(
                property == null || property.isBlank() ? null : Path.of(property),
                version,
                JkDirs.productLib(),
                fallbacks,
                release);
    }

    /** {@code jk-maven-spy-<version>.jar}. */
    public String jarName() {
        return ARTIFACT + "-" + version + ".jar";
    }

    /** Where a fetch lands, and where {@code jk doctor} tells the user to expect the jar. */
    public Path expected() {
        return lib.resolve(jarName());
    }

    /** The jar, wherever the lookup order finds it first; empty when nowhere. */
    public Optional<Path> locate() {
        List<Path> candidates = new ArrayList<>();
        if (override != null) candidates.add(override);
        candidates.add(expected());
        candidates.addAll(fallbacks);
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) return Optional.of(p.toAbsolutePath().normalize());
        }
        return Optional.empty();
    }

    /**
     * {@link #locate}, fetching the release's jar into the product library first when none is
     * found and this process may. A failed fetch is one line on stderr and an empty answer: Maven
     * still runs, without a run report, and the next {@code jk mvn} tries again.
     */
    public Optional<Path> ensure() {
        Optional<Path> found = locate();
        if (found.isPresent() || !release.fetchable()) return found;
        try (ReleaseDownloadView view = new ReleaseDownloadView(CHIP, "Maven extension " + version)) {
            fetch(view);
        } catch (IOException e) {
            CliOutput.err("jk mvn: " + jarName() + " could not be fetched: " + e.getMessage()
                    + " — Maven runs without a run report");
            return Optional.empty();
        }
        return locate();
    }

    /**
     * Download and verify the release's jar, then place it at {@link #expected()} through a
     * sibling temp file and an atomic move, so a torn download is never a jar Maven loads.
     */
    public Path fetch(ReleaseArtifacts.Progress progress) throws IOException {
        ReleaseArtifacts.Verified jar = ReleaseArtifacts.fetch(
                release.base(), version, jarName(), "Maven spy jar", release.verifier(), progress);
        Files.createDirectories(lib);
        Path target = expected();
        Path tmp = Files.createTempFile(lib, jarName() + ".", ".tmp");
        try {
            Files.write(tmp, jar.bytes());
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
        progress.done(target);
        return target;
    }

    /**
     * Maven's argv with the spy attached: {@code -Dmaven.ext.class.path} naming the jar (joined
     * onto the user's own, when they passed one) and {@code -Djk.mvn.events} naming the file.
     */
    static List<String> arguments(Path jar, Path events, List<String> args) {
        List<String> out = new ArrayList<>(args.size() + 2);
        String extension = "-D" + EXTENSION_PROPERTY + "=";
        boolean merged = false;
        for (String a : args) {
            if (a.startsWith(extension)) {
                out.add(a + Classpaths.SEPARATOR + jar);
                merged = true;
            } else {
                out.add(a);
            }
        }
        if (!merged) out.add(0, extension + jar);
        out.add(0, "-D" + EVENTS_PROPERTY + "=" + events);
        return out;
    }

    /** The directory holding the running client binary; empty for a JVM whose command is not a file. */
    private static Optional<Path> clientDir() {
        return ProcessHandle.current().info().command().map(Path::of).map(Path::getParent);
    }
}
