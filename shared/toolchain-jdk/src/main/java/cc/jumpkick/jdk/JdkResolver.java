// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import cc.jumpkick.lock.JdkPin;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Reads and resolves the project's {@code .jdk-version} pin — the {@code .jdk-version}
 * <em>tier</em> of the resolution order. The full cross-tier order (switch / env / {@code
 * .jdk-version} / lock / {@code jdk} / current / default / …) lives in {@link
 * JdkResolution}; this class only owns the file: read it ({@link #readJdkVersion}), validate it
 * ({@link #validatePin}), and resolve it to an installed (or provisioned) JDK.
 *
 * <p>A patch-level pin ({@code temurin-25.0.2}) or a vendorless one ({@code 25}) is rejected with
 * an {@link IllegalArgumentException}: jk keeps the patch current via the stable {@code
 * <vendor>-<major>} pointer, so a patch pin would fight its aggressive point-release upgrades.
 */
public final class JdkResolver {

    private final JdkRegistry registry;

    public JdkResolver(JdkRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public Optional<InstalledJdk> resolve(Path projectDir) throws IOException {
        Optional<String> pin = readJdkVersion(projectDir);
        if (pin.isEmpty()) return Optional.empty();
        String spec = validatePin(pin.get());
        Optional<InstalledJdk> direct = registry.findBySpec(spec);
        if (direct.isEmpty()) {
            direct = new JdkProvisioning(registry).resolve(JdkSpec.parse(spec)).map(JdkProvisioning.Result::jdk);
        }
        // The project's requirement binds this tier as it binds the build: a pinned JDK below it
        // gives way to an installed one that meets it, else to none.
        JdkPin required = LockPinMatch.withRequirement(null, JdkRequirementScan.of(projectDir));
        if (required == null || direct.isEmpty()) return direct;
        List<JdkHit> hits = registry.listHits();
        Optional<JdkHit> pinned = LockPinMatch.hitFor(direct.get().home(), hits);
        if (pinned.isPresent() && LockPinMatch.satisfies(pinned.get(), required)) return direct;
        return LockPinMatch.choose(hits, required)
                .map(h -> new InstalledJdk(JdkRegistry.identifierFor(h.home()), h.home()));
    }

    /**
     * Enforce the {@code <vendor>-<major>} pin format. Returns the trimmed spec; throws {@link
     * IllegalArgumentException} for patch-level or vendorless pins.
     */
    static String validatePin(String raw) {
        String pin = raw.trim();
        JdkSelector.FlexibleQuery q = JdkSelector.parseFlexible(pin);
        if (q.majorOpt().isEmpty()
                || q.exactVersionOpt().isPresent()
                || q.hints().isEmpty()) {
            throw new IllegalArgumentException(
                    ".jdk-version must be <vendor>-<major>, e.g. \"temurin-25\", not \"" + pin + "\"");
        }
        return pin;
    }

    public static Optional<String> readJdkVersion(Path projectDir) throws IOException {
        Path file = projectDir.resolve(".jdk-version");
        if (!Files.exists(file)) return Optional.empty();
        String body = Files.readString(file).trim();
        return body.isEmpty() ? Optional.empty() : Optional.of(body);
    }

    /**
     * Convenience for CLI commands that want the project's pinned JDK with an optional {@code
     * --jdks-dir} override. {@code jdksDirOverride} may be {@code null} to use the IntelliJ JDK
     * directory default.
     */
    public static Optional<InstalledJdk> forProject(Path projectDir, @Nullable Path jdksDirOverride)
            throws IOException {
        return new JdkResolver(JdkEnsure.sharedRegistry(jdksDirOverride, projectDir)).resolve(projectDir);
    }
}
