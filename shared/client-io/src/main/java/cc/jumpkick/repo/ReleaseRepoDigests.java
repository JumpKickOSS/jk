// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The sha256 of every first-party jar and POM a release published on the official repository, as
 * the release's {@code jk-repo-<version>.sha256} lists them: one {@code <hex>  <path>} line per
 * file, the path Maven layout under the repository root ({@code
 * cc/jumpkick/jk-publisher/1.0.0/jk-publisher-1.0.0.jar}). The file is an artifact of the release
 * directory, so the signed {@code SHA256SUMS} vouches for it and it vouches for each file on the
 * repository, whichever mirror serves it.
 *
 * @param version the release the digests belong to
 * @param byPath lower-case sha256 per repository-relative path
 */
public record ReleaseRepoDigests(String version, Map<String, String> byPath) {

    /**
     * One line: 64 hex digits, two spaces, a relative path of plain segments. A segment starts
     * with a letter, digit or underscore, so {@code .} and {@code ..} never match.
     */
    private static final Pattern LINE =
            Pattern.compile("^([0-9a-f]{64})  ((?:[A-Za-z0-9_][A-Za-z0-9_.+-]*/)*" + "[A-Za-z0-9_][A-Za-z0-9_.+-]*)$");

    public ReleaseRepoDigests {
        byPath = Map.copyOf(byPath);
    }

    /** The release artifact that lists the repository's digests for {@code version}. */
    public static String fileName(String version) {
        return "jk-repo-" + version + ".sha256";
    }

    /** The digest the release lists for {@code relPath}, if it published that file. */
    public Optional<String> sha256(String relPath) {
        return Optional.ofNullable(byPath.get(relPath));
    }

    /**
     * The digests of {@code version}'s release under {@code releasesBase}: its {@code SHA256SUMS}
     * verified against {@code verifier}, then the digest list fetched and checked against it. A
     * release that is not published throws {@link ReleaseArtifacts.NotPublished}.
     */
    public static ReleaseRepoDigests fetch(URI releasesBase, String version, ReleaseVerifier verifier)
            throws IOException {
        ReleaseArtifacts.Verified file = ReleaseArtifacts.manifest(releasesBase, version, verifier)
                .fetch(fileName(version), "first-party repository digests", ReleaseArtifacts.Progress.NONE);
        return parse(version, file.bytes());
    }

    /**
     * Parse a digest list. Strict, as the signed manifest is: LF lines, lower-case hex, plain
     * relative paths, each path once.
     */
    public static ReleaseRepoDigests parse(String version, byte[] bytes) throws IOException {
        String text;
        try {
            text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IOException(fileName(version) + " is not UTF-8", e);
        }
        Map<String, String> byPath = new HashMap<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty() && i == lines.length - 1) continue;
            Matcher m = LINE.matcher(line);
            if (!m.matches()) throw new IOException(fileName(version) + " has a malformed line " + (i + 1));
            String path = m.group(2);
            if (byPath.put(path, m.group(1)) != null) {
                throw new IOException(fileName(version) + " lists " + path + " twice");
            }
        }
        return new ReleaseRepoDigests(version, byPath);
    }
}
