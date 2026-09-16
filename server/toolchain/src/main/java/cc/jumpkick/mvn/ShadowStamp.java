// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkVersion;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;

/**
 * The header of a shadow manifest: which POM files it was rendered from and their digest, so a
 * reader tells a current shadow from a stale one without importing anything.
 *
 * <pre>
 * # shadow of pom.xml &lt;sha256&gt; jk &lt;version&gt;
 * # read pom.xml
 * # read ../pom.xml
 * </pre>
 *
 * <p>The paths are relative to the module directory with {@code /} separators; the digest covers
 * their bytes in the listed order, so a shadow is current exactly when every file it read still
 * has the bytes it read and the running jk is the one that rendered it. {@link #chain} names the
 * files one POM reads from disk: itself and the parents its {@code <relativePath>} reaches.
 */
final class ShadowStamp {

    private ShadowStamp() {}

    /** Prefix of the shadow's first line; the rest is the digest and the rendering jk's version. */
    static final String HEADER = "# shadow of " + ManifestPaths.POM + " ";

    /** Prefix of one input line. */
    static final String READ = "# read ";

    /** Bounds the climb through {@code <parent>} blocks that point at each other. */
    private static final int MAX_DEPTH = 32;

    /**
     * The POM files {@code pom} reads from disk, nearest first: itself, then each parent whose
     * {@code <relativePath>} (default {@code ../pom.xml}) names a file that declares that parent's
     * {@code artifactId}. A blank {@code <relativePath/>} tells Maven to look only in repositories,
     * and the climb stops there too.
     */
    static List<Path> chain(Path pom) {
        List<Path> out = new ArrayList<>();
        Path current = pom.toAbsolutePath().normalize();
        out.add(current);
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            Parent parent;
            try {
                parent = EffectiveModel.rawModel(Files.readAllBytes(current)).getParent();
            } catch (IOException | RuntimeException unreadable) {
                break;
            }
            if (parent == null) break;
            String relativePath = parent.getRelativePath();
            if (relativePath == null || relativePath.isBlank()) break;
            Path candidate = Objects.requireNonNull(current.getParent())
                    .resolve(relativePath.trim())
                    .normalize();
            if (Files.isDirectory(candidate)) candidate = candidate.resolve(ManifestPaths.POM);
            if (!Files.isRegularFile(candidate) || out.contains(candidate)) break;
            if (!declares(candidate, parent.getArtifactId())) break;
            out.add(candidate);
            current = candidate;
        }
        return out;
    }

    private static boolean declares(Path pom, String artifactId) {
        try {
            Model raw = EffectiveModel.rawModel(Files.readAllBytes(pom));
            return artifactId.equals(raw.getArtifactId());
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    /** The header lines for a shadow of {@code moduleDir} rendered from {@code inputs}, newline-terminated. */
    static String header(Path moduleDir, Collection<Path> inputs) {
        List<String> rels = new ArrayList<>();
        for (Path input : inputs) rels.add(relative(moduleDir, input));
        StringBuilder sb = new StringBuilder();
        sb.append(HEADER)
                .append(digest(moduleDir, rels))
                .append(" jk ")
                .append(JkVersion.VERSION)
                .append('\n');
        for (String rel : rels) sb.append(READ).append(rel).append('\n');
        return sb.toString();
    }

    /** True when {@code shadow} exists, this jk rendered it, and every file it read is unchanged. */
    static boolean isCurrent(Path shadow, Path moduleDir) {
        if (!Files.isRegularFile(shadow)) return false;
        String first = null;
        List<String> rels = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(shadow, StandardCharsets.UTF_8)) {
                if (first == null) {
                    if (!line.startsWith(HEADER)) return false;
                    first = line;
                } else if (line.startsWith(READ)) {
                    rels.add(line.substring(READ.length()));
                } else {
                    break;
                }
            }
        } catch (IOException e) {
            return false;
        }
        if (first == null || rels.isEmpty()) return false;
        return first.equals(HEADER + digest(moduleDir, rels) + " jk " + JkVersion.VERSION);
    }

    /** Hex SHA-256 over the listed files' paths and contents; a missing file yields a digest nothing matches. */
    private static String digest(Path moduleDir, List<String> rels) {
        MessageDigest md = Hashing.newSha256();
        for (String rel : rels) {
            Path file = moduleDir.resolve(rel).normalize();
            try {
                md.update((rel + "\n").getBytes(StandardCharsets.UTF_8));
                md.update((Hashing.sha256Hex(file) + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (IOException missing) {
                return "unreadable:" + rel;
            }
        }
        return Hashing.hex(md.digest());
    }

    private static String relative(Path moduleDir, Path file) {
        Path module = moduleDir.toAbsolutePath().normalize();
        Path abs = file.toAbsolutePath().normalize();
        Path rel = abs.getRoot() != null && abs.getRoot().equals(module.getRoot()) ? module.relativize(abs) : abs;
        return rel.toString().replace(File.separatorChar, '/');
    }
}
