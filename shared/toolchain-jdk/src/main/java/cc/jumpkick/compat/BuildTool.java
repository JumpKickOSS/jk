// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import cc.jumpkick.host.Os;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * External tools for {@code jk mvn}/{@code jk gradle} passthroughs: cache slug and bin names.
 */
public enum BuildTool {
    MAVEN("maven", "mvn", "mvn.cmd", List.of(PublishedChecksum.SHA512, PublishedChecksum.SHA1)),
    GRADLE("gradle", "gradle", "gradle.bat", List.of(PublishedChecksum.SHA256)),
    KOTLIN("kotlin", "kotlinc", "kotlinc.bat", List.of(PublishedChecksum.SHA256));

    private final String slug;
    private final String posixBinary;
    private final String windowsBinary;
    private final List<PublishedChecksum> publishedChecksums;

    BuildTool(String slug, String posixBinary, String windowsBinary, List<PublishedChecksum> publishedChecksums) {
        this.slug = slug;
        this.posixBinary = posixBinary;
        this.windowsBinary = windowsBinary;
        this.publishedChecksums = publishedChecksums;
    }

    /**
     * The checksum sidecars this tool's publisher puts beside each archive, strongest first —
     * Apache Maven ships {@code .sha512} on Central from 3.7 on and only {@code .sha1} for the 3.6
     * line, Gradle and JetBrains ship {@code .sha256}. An unpinned distribution is verified against
     * the first one that is published.
     */
    public List<PublishedChecksum> publishedChecksums() {
        return publishedChecksums;
    }

    /** Directory name under the provisioned-tools root, {@code $JK_STORE_DIR/tools/}. */
    public String slug() {
        return slug;
    }

    /**
     * The version a user writes for "whatever jk provisions by default". Here rather than beside
     * the resolvers because the CLI parses it and the engine resolves it, and a second spelling
     * would mean {@code kotlin:latest} installing something {@code jk build} does not then use.
     */
    public static final String LATEST = "latest";

    /** Binary name under {@code <home>/bin/} on the current OS. */
    public String binaryName() {
        return Os.isWindows() ? windowsBinary : posixBinary;
    }

    /**
     * The tool a user named, by {@link #slug()}. Empty when the name is not one jk provisions.
     *
     * <p>Here rather than in a CLI parser so the names a command accepts, the names its help text
     * prints, and the directories the registry writes are all the same list. A fourth tool is an
     * enum constant.
     */
    public static Optional<BuildTool> bySlug(@Nullable String slug) {
        if (slug == null) return Optional.empty();
        String s = slug.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(t -> t.slug.equals(s)).findFirst();
    }

    /** Every slug, comma-separated — for help text and for "unknown tool" messages. */
    public static String slugs() {
        return Arrays.stream(values()).map(BuildTool::slug).collect(Collectors.joining(", "));
    }
}
