// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkVersion;
import java.io.BufferedReader;
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
import org.jspecify.annotations.Nullable;

/**
 * The header of a shadow manifest: which POM files it was rendered from and their digest, so a
 * reader tells a current shadow from a stale one without importing anything.
 *
 * <pre>
 * # shadow of pom.xml &lt;sha256&gt; jk &lt;version&gt;
 * # read pom.xml ../pom.xml .mvn/maven.config
 * </pre>
 *
 * <p>The inputs share one line, since tomlj's parse time grows faster than linearly with a run of
 * comment lines and a reactor's shadow lists every POM of the tree. The paths are relative to the
 * module directory with {@code /} separators, separated by a space, a {@code %} or whitespace
 * inside one written as {@code %XX}; the digest covers
 * their bytes in the listed order, a file that was absent counting as absent, so a shadow is
 * current exactly when every file it read still has the bytes it read, every file it found absent
 * is still absent, and the running jk is the one that rendered it. {@link #inputs} names the files
 * one POM's import reads from disk: itself, the parents its {@code <relativePath>} reaches, and
 * the build's {@link MavenConfig}.
 */
final class ShadowStamp {

    private ShadowStamp() {}

    /** Prefix of the shadow's first line; the rest is the digest and the rendering jk's version. */
    static final String HEADER = "# shadow of " + ManifestPaths.POM + " ";

    /** Prefix of the line that lists the inputs. */
    static final String READ = "# read ";

    /** What an input that does not exist contributes to the digest. */
    private static final String ABSENT = "absent";

    /** Bounds the climb through {@code <parent>} blocks that point at each other. */
    private static final int MAX_DEPTH = 32;

    /** The files an import of {@code pom} reads from disk: its {@link #chain} and its build's {@code maven.config}. */
    static List<Path> inputs(Path pom) {
        List<Path> out = chain(pom);
        out.add(MavenConfig.file(pom));
        return out;
    }

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
        sb.append(READ);
        for (int i = 0; i < rels.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(encode(rels.get(i)));
        }
        return sb.append('\n').toString();
    }

    /** True when {@code shadow} exists, this jk rendered it, and every file it read is unchanged. */
    static boolean isCurrent(Path shadow, Path moduleDir) {
        if (!Files.isRegularFile(shadow)) return false;
        String first;
        String read;
        try (BufferedReader in = Files.newBufferedReader(shadow, StandardCharsets.UTF_8)) {
            first = in.readLine();
            read = in.readLine();
        } catch (IOException e) {
            return false;
        }
        if (first == null || !first.startsWith(HEADER) || read == null || !read.startsWith(READ)) return false;
        List<String> rels = new ArrayList<>();
        for (String token : read.substring(READ.length()).split(" ")) {
            String rel = decode(token);
            if (rel == null || rel.isEmpty()) return false;
            rels.add(rel);
        }
        return first.equals(HEADER + digest(moduleDir, rels) + " jk " + JkVersion.VERSION);
    }

    /**
     * Hex SHA-256 over the listed files' paths and contents, an absent file contributing a marker no
     * content hash equals; a file that exists but cannot be read yields a digest nothing matches.
     */
    private static String digest(Path moduleDir, List<String> rels) {
        MessageDigest md = Hashing.newSha256();
        for (String rel : rels) {
            Path file = moduleDir.resolve(rel).normalize();
            try {
                String content = Files.exists(file) ? Hashing.sha256Hex(file) : ABSENT;
                md.update((rel + "\n").getBytes(StandardCharsets.UTF_8));
                md.update((content + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (IOException unreadable) {
                return "unreadable:" + rel;
            }
        }
        return Hashing.hex(md.digest());
    }

    /** {@code rel} with {@code %} and every whitespace or control character as {@code %XX}, so it holds no space. */
    static String encode(String rel) {
        StringBuilder sb = new StringBuilder(rel.length());
        for (int i = 0; i < rel.length(); i++) {
            char c = rel.charAt(i);
            if (c == '%' || c <= ' ' || c == 0x7f) sb.append('%').append(Hashing.hex(new byte[] {(byte) c}));
            else sb.append(c);
        }
        return sb.toString();
    }

    /** The path {@link #encode} wrote as {@code token}, or null when a {@code %} is not followed by two hex digits. */
    static @Nullable String decode(String token) {
        StringBuilder sb = new StringBuilder(token.length());
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c != '%') {
                sb.append(c);
                continue;
            }
            String hex = token.substring(i + 1, Math.min(i + 3, token.length()));
            if (!Hashing.isHex(hex, 2)) return null;
            sb.append((char) (Hashing.unhex(hex)[0] & 0xff));
            i += 2;
        }
        return sb.toString();
    }

    private static String relative(Path moduleDir, Path file) {
        Path module = moduleDir.toAbsolutePath().normalize();
        Path abs = file.toAbsolutePath().normalize();
        Path rel = abs.getRoot() != null && abs.getRoot().equals(module.getRoot()) ? module.relativize(abs) : abs;
        return rel.toString().replace(File.separatorChar, '/');
    }
}
