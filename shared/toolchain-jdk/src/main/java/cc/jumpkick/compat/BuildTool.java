// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import cc.jumpkick.host.Os;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * External tools for {@code jk mvn}/{@code jk gradle} passthroughs: cache slug and bin names.
 */
public enum BuildTool {
    MAVEN("maven", "mvn", "mvn.cmd"),
    GRADLE("gradle", "gradle", "gradle.bat"),
    KOTLIN("kotlin", "kotlinc", "kotlinc.bat");

    private final String slug;
    private final String posixBinary;
    private final String windowsBinary;

    BuildTool(String slug, String posixBinary, String windowsBinary) {
        this.slug = slug;
        this.posixBinary = posixBinary;
        this.windowsBinary = windowsBinary;
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
    public static Optional<BuildTool> bySlug(String slug) {
        if (slug == null) return Optional.empty();
        String s = slug.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(t -> t.slug.equals(s)).findFirst();
    }

    /** Every slug, comma-separated — for help text and for "unknown tool" messages. */
    public static String slugs() {
        return Arrays.stream(values()).map(BuildTool::slug).collect(Collectors.joining(", "));
    }
}
