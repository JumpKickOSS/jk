// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.discovery;

import cc.jumpkick.jdk.IntellijJdkDir;
import cc.jumpkick.jdk.JdkHit;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Surfaces JDKs that Gradle's toolchain auto-provisioning placed under {@code <gradle-user-home>/jdks/}.
 * Unlike {@code the managed JDK root} (flat), Gradle nests each install one level down — {@code
 * ~/.gradle/jdks/<vendor-os-arch-hash>/<jdk-dir>/} — so this probe walks two levels and applies the
 * macOS {@code Contents/Home} unwrap ({@link IntellijJdkDir#javaHome}) before discovery. Source
 * label {@code "gradle"}.
 */
public final class GradleProbe implements LocalToolProbe {

    private final Path jdksRoot;

    public GradleProbe() {
        this(gradleUserHome(JkDirs::env, System.getProperty("user.home")).resolve("jdks"));
    }

    GradleProbe(Path jdksRoot) {
        this.jdksRoot = jdksRoot;
    }

    /** {@code GRADLE_USER_HOME} when set, else {@code ~/.gradle}. */
    static Path gradleUserHome(Function<String, @Nullable String> env, String userHome) {
        String configured = env.apply("GRADLE_USER_HOME");
        return configured != null && !configured.isBlank() ? Path.of(configured) : Path.of(userHome, ".gradle");
    }

    @Override
    public String name() {
        return "gradle";
    }

    @Override
    public Optional<DiscoveredTool> find(ToolSpec spec) throws IOException {
        if (!"java".equals(spec.kind())) return Optional.empty();
        for (Path home : candidateHomes()) {
            if (ToolHealth.isHealthy(spec, home)) {
                return Optional.of(new DiscoveredTool(home, spec.version(), name()));
            }
        }
        return Optional.empty();
    }

    @Override
    public List<JdkHit> discoverAllJdks() throws IOException {
        List<JdkHit> hits = new ArrayList<>();
        for (Path home : candidateHomes()) {
            ProbeSupport.discoverJdk(home, name()).ifPresent(hits::add);
        }
        return hits;
    }

    /** Gradle's layout: each provisioned JDK sits one level below the root. */
    private List<Path> candidateHomes() throws IOException {
        if (!Files.isDirectory(jdksRoot)) return List.of();
        List<Path> homes = new ArrayList<>();
        try (Stream<Path> hashDirs = Files.list(jdksRoot)) {
            for (Path hashDir : (Iterable<Path>) hashDirs::iterator) {
                if (!Files.isDirectory(hashDir)
                        || hashDir.getFileName().toString().startsWith(".")) continue;
                try (Stream<Path> inner = Files.list(hashDir)) {
                    inner.filter(Files::isDirectory)
                            .filter(p -> !p.getFileName().toString().startsWith("."))
                            .map(IntellijJdkDir::javaHome)
                            .forEach(homes::add);
                }
            }
        }
        return homes;
    }
}
