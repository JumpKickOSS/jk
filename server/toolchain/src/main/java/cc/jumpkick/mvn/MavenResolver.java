// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.BuildTool;
import cc.jumpkick.compat.ToolDistribution;
import cc.jumpkick.compat.WrapperDistribution;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.version.Versions;
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
 * Picks the Maven distribution: the wrapper's {@code distributionUrl} when the project has one,
 * else the newest Maven 3.9.x jk knows — or the higher floor the POM's enforcer rule states
 * ({@link EnforcerMavenVersion}), so a repo that requires a newer Maven is not run on an older one.
 */
public final class MavenResolver {

    /**
     * The newest Maven 3.9.x jk knows: the release the Maven spy ({@code clients/maven-spy})
     * compiles against, moved together with that pin.
     */
    public static final String DEFAULT_VERSION = "3.9.16";

    /** Apache Maven's own distribution zips, published to Central like any other artifact. */
    private static final String DEFAULT_BASE = RepositorySpec.MAVEN_CENTRAL.url() + "org/apache/maven/apache-maven/";

    // apache-maven-<version>-bin.<zip|tar.gz>
    private static final Pattern FILENAME_VERSION = Pattern.compile("apache-maven-(?<v>[^/]+)-bin\\.(?:zip|tar\\.gz)$");

    public ToolDistribution resolve(Path projectDir) throws IOException {
        Path wrapperProps = projectDir.resolve(".mvn/wrapper/maven-wrapper.properties");
        if (Files.exists(wrapperProps)) {
            ToolDistribution fromWrapper = fromWrapperProperties(wrapperProps);
            if (fromWrapper != null) return fromWrapper;
        }
        return defaultDistribution(projectDir);
    }

    public static ToolDistribution defaultDistribution() {
        return distributionFor(DEFAULT_VERSION);
    }

    /**
     * The default for a project with no wrapper: {@link #DEFAULT_VERSION}, or the enforcer's floor
     * when the POM asks for a newer Maven than that.
     */
    static ToolDistribution defaultDistribution(Path projectDir) {
        String floor = EnforcerMavenVersion.minimum(projectDir);
        boolean above = floor != null && Versions.compare(floor, DEFAULT_VERSION) > 0;
        return distributionFor(above ? floor : DEFAULT_VERSION);
    }

    /** The distribution for an explicit Maven version; blank means {@link #DEFAULT_VERSION}. */
    public static ToolDistribution distributionFor(@Nullable String version) {
        String v = version == null || version.isBlank() ? DEFAULT_VERSION : version.trim();
        URI uri = URI.create(DEFAULT_BASE + v + "/apache-maven-" + v + "-bin.zip");
        return new ToolDistribution(BuildTool.MAVEN, v, uri, "zip");
    }

    static @Nullable ToolDistribution fromWrapperProperties(Path file) throws IOException {
        Properties props = new Properties();
        try (var in = Files.newInputStream(file)) {
            props.load(in);
        }
        String url = props.getProperty("distributionUrl");
        if (url == null || url.isBlank()) return null;
        URI uri = WrapperDistribution.secureUrl(url, file);
        String archiveType = url.endsWith(".tar.gz") ? "tar.gz" : "zip";
        String version = parseVersion(uri).orElse("wrapper");
        String sha256 = props.getProperty("distributionSha256Sum");
        return new ToolDistribution(
                BuildTool.MAVEN, version, uri, archiveType, sha256 == null || sha256.isBlank() ? null : sha256.trim());
    }

    static Optional<String> parseVersion(URI uri) {
        String path = uri.getPath();
        if (path == null) return Optional.empty();
        Matcher m = FILENAME_VERSION.matcher(path);
        return m.find() ? Optional.of(m.group("v")) : Optional.empty();
    }
}
