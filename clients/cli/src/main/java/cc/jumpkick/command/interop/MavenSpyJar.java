// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.interop;

import cc.jumpkick.host.Classpaths;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.repo.RepoArtifactResolver;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Where the Maven event spy jar ({@code jk-maven-spy-<version>.jar}) lives. Looked up in this order,
 * first hit wins: the {@code jk.maven-spy.jar} system property; the product library
 * ({@code <home>/lib/}, where {@code install.sh} puts it); the store's {@code jk-local} shelf
 * ({@code jk install} from a checkout); {@code lib/} beside the running client (the dist layout).
 * The lookup is also documented in {@code docs/user/migration.md}.
 */
final class MavenSpyJar {

    /** Explicit override, for tests and for a checkout that has not been installed. */
    static final String PROPERTY = "jk.maven-spy.jar";

    /** The spy's artifact name; the jar is {@code <name>-<version>.jar}. */
    static final String ARTIFACT = "jk-maven-spy";

    /** Every -D the spy needs: its own presence on Maven's extension path and the events file. */
    static final String EXTENSION_PROPERTY = "maven.ext.class.path";

    static final String EVENTS_PROPERTY = "jk.mvn.events";

    private MavenSpyJar() {}

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

    static Optional<Path> locate() {
        return locate(System.getProperty(PROPERTY), JkVersion.VERSION);
    }

    static Optional<Path> locate(@Nullable String override, String version) {
        String jar = ARTIFACT + "-" + version + ".jar";
        List<Path> candidates = new ArrayList<>();
        if (override != null && !override.isBlank()) candidates.add(Path.of(override));
        candidates.add(JkDirs.productLib().resolve(jar));
        RepoArtifactStore.forStoreId(JkDirs.store(), RepoArtifactResolver.JK_LOCAL)
                .locate("cc/jumpkick/" + ARTIFACT + "/" + version + "/" + jar)
                .ifPresent(candidates::add);
        clientDir().ifPresent(dir -> candidates.add(dir.resolve("lib").resolve(jar)));
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) return Optional.of(p.toAbsolutePath().normalize());
        }
        return Optional.empty();
    }

    /** The directory holding the running client binary; empty for a JVM whose command is not a file. */
    private static Optional<Path> clientDir() {
        return ProcessHandle.current().info().command().map(Path::of).map(Path::getParent);
    }
}
