// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Properties;

/**
 * Reads the secret a {@code ${file:path}} or {@code ${file:path#key}} reference, or a {@code
 * JK_REPO_<ID>_*_FILE} variable, names.
 *
 * <p><b>Permissions.</b> On POSIX a file that its group or others can read is refused, naming the
 * fix, except a container platform's secret mount that only root or the user can write; {@link
 * SecretFileAccess} has the rule and the threat it accepts. Windows has no cheap equivalent and is
 * not checked.
 *
 * <p><b>Rotation.</b> Every read stats the file, and the cached contents are reused only while the
 * file's identity, modification time and size are unchanged. A rotated secret is therefore picked
 * up on the next resolve, and a resident engine never needs restarting for it.
 *
 * <p>Every failure is a {@link JkBuildParseException} that starts with {@code where}, the position
 * that named the file ({@code repositories.<id>.<key>} or the variable), so a missing file or key
 * is never a silent anonymous request.
 */
public final class SecretFiles {

    /** What each file held when last read, under its settled stat; a machine names a handful. */
    private static final StampedMemo<Path, StampedMemo.FileStamp, String> CACHE = StampedMemo.bounded(256);

    private SecretFiles() {}

    /**
     * The value of a {@code file:} reference body — {@code path} or {@code path#key} — with a
     * relative path resolved against {@code baseDir}, the declaring file's directory.
     */
    public static String reference(String body, Path baseDir, String where) {
        int hash = body.lastIndexOf('#');
        String path = hash < 0 ? body : body.substring(0, hash);
        String key = hash < 0 ? null : body.substring(hash + 1).strip();
        if (path.isBlank()) throw new JkBuildParseException(where + " has a ${file:} reference with no path");
        if (key != null && key.isEmpty()) {
            throw new JkBuildParseException(where + " has a ${file:" + body + "} reference with an empty key after #");
        }
        Path file = resolve(path.strip(), baseDir);
        return key == null ? contents(file, where) : property(file, key, where);
    }

    /** {@code raw} with a leading {@code ~} expanded, and a relative path resolved against {@code baseDir}. */
    public static Path resolve(String raw, Path baseDir) {
        if (raw.equals("~") || raw.startsWith("~/") || raw.startsWith("~\\")) return PathUtil.resolveUserPath(raw);
        Path p = Path.of(raw);
        return (p.isAbsolute() ? p : baseDir.resolve(p)).toAbsolutePath().normalize();
    }

    /** The whole of {@code file}, trailing line terminators trimmed; an empty file is an error. */
    public static String contents(Path file, String where) {
        String value = stripTrailingNewlines(read(file, where));
        if (value.isEmpty()) throw new JkBuildParseException(where + " reads " + file + ", which is empty");
        return value;
    }

    /** One key of {@code file} read as a Java {@code .properties} file; a missing key is an error. */
    public static String property(Path file, String key, String where) {
        Properties props = new Properties();
        try {
            props.load(new StringReader(read(file, where)));
        } catch (IOException | IllegalArgumentException e) {
            throw new JkBuildParseException(
                    where + " reads " + file + ", which is not a valid .properties file: " + e.getMessage());
        }
        String value = props.getProperty(key);
        if (value == null) {
            throw new JkBuildParseException(where + " reads key `" + key + "` of " + file + ", which has no such key");
        }
        if (value.isEmpty()) {
            throw new JkBuildParseException(where + " reads key `" + key + "` of " + file + ", which is empty");
        }
        return value;
    }

    /** The file's text, from the cache while its stat is unchanged, after the permission check. */
    private static String read(Path file, String where) {
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(file, BasicFileAttributes.class);
        } catch (NoSuchFileException e) {
            throw new JkBuildParseException(where + " reads " + file + ", which does not exist");
        } catch (IOException e) {
            throw new JkBuildParseException(where + " cannot read " + file + ": " + e.getMessage());
        }
        if (!attrs.isRegularFile()) throw new JkBuildParseException(where + " reads " + file + ", which is not a file");
        SecretFileAccess.DEFAULT.check(file, where);
        StampedMemo.FileStamp stamp = StampedMemo.FileStamp.of(file);
        if (stamp == null) return readText(file, where);
        return CACHE.get(file, stamp, () -> readText(file, where));
    }

    private static String readText(Path file, String where) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new JkBuildParseException(where + " cannot read " + file + ": " + e.getMessage());
        }
    }

    private static String stripTrailingNewlines(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) end--;
        return s.substring(0, end);
    }

    /** Forget every cached read. Nothing on the build path needs this; tests do. */
    static void clear() {
        CACHE.clear();
    }
}
