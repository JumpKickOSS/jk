// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What a test or a test compile is keyed on of the files {@code [build-info]} writes: their stable
 * values, never the volatile ones. {@code git.properties} and Boot's {@code
 * META-INF/build-info.properties} carry the checkout's commit, branch, dirty state and times beside
 * the project's name and version. The volatile values move with every commit, and {@code git.dirty}
 * with the first edit anywhere in the checkout, so a stamp that hashed the files whole re-ran the
 * module's suite for an edit it cannot observe.
 *
 * <p>The rule is the one jk keys its own engine by ({@code BuildIdentity.keyId} leaves the
 * packaging's {@code Build-Commit} and {@code Build-Time} stamps out of the code's identity), the
 * one Bazel applies to its volatile status keys, and what Gradle's runtime classpath normalization
 * ({@code properties("**}{@code /build-info.properties") { ignoreProperty("build.time") }}) is
 * configured to do by hand: a stable value keys, a volatile one does not, unless a test
 * demonstrably reads it. "Demonstrably" is the suite's own sources naming one ({@link #namedBy}):
 * a key, the file, or the Boot accessor that returns one. Such a suite keys the files whole.
 */
public final class BuildInfoKeys {

    private BuildInfoKeys() {}

    /** Where Boot's {@code BuildProperties} reads its keys from. */
    public static final String BOOT_ENTRY = "META-INF/build-info.properties";

    /** The keys that move with the checkout or the clock rather than with the project. */
    static final Set<String> VOLATILE = Set.of(
            "git.branch",
            "git.build.time",
            "git.commit.id",
            "git.commit.id.abbrev",
            "git.commit.time",
            "git.dirty",
            "build.time");

    /**
     * What in a test source shows it reads a volatile value: the {@code git.} keys and the file
     * named for them, Boot's build properties file and its time key, and the accessors Boot's
     * {@code GitProperties} and {@code BuildProperties} return them by.
     */
    static final List<String> READ_MARKS = List.of(
            "git.",
            "build-info.properties",
            "build.time",
            "GitProperties",
            "getCommitId",
            "getShortCommitId",
            "getCommitTime",
            "getBranch",
            "BuildProperties");

    /**
     * The digest of {@code content}, a properties file, less its volatile entries: the stable
     * entries in file order, each line as written. A line is an entry's when it is not a comment
     * and its key (up to the first unescaped {@code =}, {@code :} or blank) is one of {@link
     * #VOLATILE}.
     */
    public static String stableDigest(byte[] content) {
        List<String> kept = new ArrayList<>();
        for (String line : new String(content, StandardCharsets.UTF_8).split("\r?\n", -1)) {
            String t = line.strip();
            if (!t.isEmpty() && t.charAt(0) != '#' && t.charAt(0) != '!' && VOLATILE.contains(key(t))) continue;
            kept.add(line);
        }
        return Hashing.sha256Hex(String.join("\n", kept));
    }

    /** The key of a properties line, unescaped as far as the build-info keys need. */
    static String key(String line) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length()) {
                sb.append(line.charAt(++i));
                continue;
            }
            if (c == '=' || c == ':' || Character.isWhitespace(c)) break;
            sb.append(c);
        }
        return sb.toString();
    }

    /** Whether any of {@code sources} names a volatile build-info value ({@link #READ_MARKS}). */
    public static boolean namedBy(Collection<Path> sources) throws IOException {
        for (Path src : sources) {
            if (!Files.isRegularFile(src)) continue;
            String text = Files.readString(src, StandardCharsets.UTF_8);
            for (String mark : READ_MARKS) {
                if (text.contains(mark)) return true;
            }
        }
        return false;
    }

    /**
     * The {@code dir:} token of the classes tree {@code classes} with each of {@code files}
     * (tree-relative) keyed by its {@link #stableDigest}: {@link ClasspathFingerprint#entry} of the
     * same tree but for those files.
     */
    public static String stableEntry(Path classes, Collection<String> files) throws IOException {
        Map<String, String> digests = new TreeMap<>(ClasspathFingerprint.digests(classes));
        for (String rel : files) {
            Path f = classes.resolve(rel);
            if (digests.containsKey(rel) && Files.isRegularFile(f)) {
                digests.put(rel, "stable:" + stableDigest(Files.readAllBytes(f)));
            }
        }
        return ClasspathFingerprint.entryFromOutputDigests(digests);
    }
}
