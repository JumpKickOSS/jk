// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.util.MinimalToml;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The version arithmetic of a release: which declared versions can be released, and the version
 * that follows one. A declared version is the next release, never a snapshot or a pre-release.
 */
public final class ReleaseVersion {

    /** The segment a release moves the declared version past. */
    public enum Segment {
        MAJOR,
        MINOR,
        PATCH
    }

    private static final Pattern PLAIN = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");

    private static final Pattern DECLARED = Pattern.compile("^version[ \t]*=[ \t]*\"([^\"]*)\"", Pattern.MULTILINE);

    private ReleaseVersion() {}

    /**
     * {@code content} with the one group of every match of {@code pattern} replaced by
     * {@code version}.
     *
     * @throws IllegalArgumentException when the pattern matches nothing: a file that no longer
     *     carries the version would otherwise be skipped in silence
     */
    public static String rewriteGroup(String content, String pattern, String version) {
        Matcher m = Pattern.compile(pattern).matcher(content);
        StringBuilder out = new StringBuilder();
        int from = 0;
        boolean found = false;
        while (m.find()) {
            found = true;
            out.append(content, from, m.start(1)).append(version);
            from = m.end(1);
        }
        if (!found) throw new IllegalArgumentException("pattern `" + pattern + "` matches nothing");
        return out.append(content, from, content.length()).toString();
    }

    /** The version literal a manifest declares at its root; empty when it inherits the version or has none. */
    public static Optional<String> declared(String manifest) {
        int firstTable = manifest.indexOf("\n[");
        String root = firstTable < 0 ? manifest : manifest.substring(0, firstTable);
        Matcher m = DECLARED.matcher(root);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    /** {@code manifest} with its root version literal replaced by {@code version}; comments, spacing and key order survive. */
    public static String rewrite(String manifest, String version) {
        if (declared(manifest).isEmpty()) {
            throw new IllegalArgumentException("the manifest declares no `version = \"...\"` literal to rewrite");
        }
        return JkBuildEditor.setRootScalar(manifest, "version", MinimalToml.quote(version));
    }

    /**
     * The version declared after releasing {@code released}: {@code segment} goes up by one and the
     * segments below it reset to zero.
     *
     * @throws IllegalArgumentException when {@code released} is not {@code MAJOR.MINOR.PATCH}, with the fix
     */
    public static String next(String released, Segment segment) {
        Matcher m = requirePlain(released);
        long major = Long.parseLong(m.group(1));
        long minor = Long.parseLong(m.group(2));
        long patch = Long.parseLong(m.group(3));
        return switch (segment) {
            case MAJOR -> (major + 1) + ".0.0";
            case MINOR -> major + "." + (minor + 1) + ".0";
            case PATCH -> major + "." + minor + "." + (patch + 1);
        };
    }

    /**
     * Check that {@code declared} can be released as written.
     *
     * @throws IllegalArgumentException with the fix when it is a snapshot, a pre-release or not three numbers
     */
    public static void requireReleasable(String declared) {
        requirePlain(declared);
    }

    private static Matcher requirePlain(String version) {
        Matcher m = PLAIN.matcher(version);
        if (m.matches()) return m;
        if (version.endsWith("-SNAPSHOT")) {
            throw new IllegalArgumentException("version `" + version + "` is a snapshot. jk.toml declares the next"
                    + " release (never -SNAPSHOT): write `" + version.substring(0, version.length() - 9)
                    + "` and let `jk snapshot` add the suffix.");
        }
        throw new IllegalArgumentException("version `" + version + "` is not a release version of the form"
                + " MAJOR.MINOR.PATCH (for example 1.4.0); a release cannot be cut from it.");
    }
}
