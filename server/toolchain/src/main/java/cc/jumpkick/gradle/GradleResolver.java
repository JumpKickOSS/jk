// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import cc.jumpkick.compat.BuildTool;
import cc.jumpkick.compat.ToolDistribution;
import cc.jumpkick.compat.ToolRegistry;
import cc.jumpkick.compat.WrapperDistribution;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Picks the Gradle distribution: wrapper {@code distributionUrl}, else {@link #DEFAULT_VERSION}.
 */
public final class GradleResolver {

    /** jk's bundled default when no wrapper is present. */
    public static final String DEFAULT_VERSION = "9.8.0";

    private static final String DEFAULT_BASE = "https://services.gradle.org/distributions/";

    // gradle-<version>-(bin|all).zip
    private static final Pattern FILENAME_VERSION = Pattern.compile("gradle-(?<v>[^/]+)-(?:bin|all)\\.zip$");

    public ToolDistribution resolve(Path projectDir) throws IOException {
        Path wrapperProps = projectDir.resolve("gradle/wrapper/gradle-wrapper.properties");
        if (Files.exists(wrapperProps)) {
            ToolDistribution fromWrapper = fromWrapperProperties(wrapperProps);
            if (fromWrapper != null) return fromWrapper;
        }
        return defaultDistribution();
    }

    public static ToolDistribution defaultDistribution() {
        return distributionFor(DEFAULT_VERSION);
    }

    /** The distribution for an explicit Gradle version; blank means {@link #DEFAULT_VERSION}. */
    public static ToolDistribution distributionFor(@Nullable String version) {
        String v = version == null || version.isBlank() ? DEFAULT_VERSION : ToolRegistry.requireVersion(version);
        URI uri = URI.create(DEFAULT_BASE + "gradle-" + v + "-bin.zip");
        return new ToolDistribution(BuildTool.GRADLE, v, uri, "zip");
    }

    static @Nullable ToolDistribution fromWrapperProperties(Path file) throws IOException {
        Properties props = new Properties();
        try (var in = Files.newInputStream(file)) {
            props.load(in);
        }
        String url = props.getProperty("distributionUrl");
        if (url == null || url.isBlank()) return null;
        URI uri = WrapperDistribution.secureUrl(url, file);
        String version = parseVersion(uri).orElse("wrapper");
        if (ToolRegistry.invalidVersion(version) != null) {
            throw new IOException("wrapper distribution version is not a single directory name: " + version);
        }
        String sha256 = props.getProperty("distributionSha256Sum");
        return new ToolDistribution(
                BuildTool.GRADLE, version, uri, "zip", sha256 == null || sha256.isBlank() ? null : sha256.trim());
    }

    static Optional<String> parseVersion(URI uri) {
        String path = uri.getPath();
        if (path == null) return Optional.empty();
        Matcher m = FILENAME_VERSION.matcher(path);
        return m.find() ? Optional.of(m.group("v")) : Optional.empty();
    }
}
