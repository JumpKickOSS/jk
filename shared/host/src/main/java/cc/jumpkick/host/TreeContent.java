// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.function.Predicate;

/**
 * Which files of a build tree are its content: every regular file except a {@link BuildStamps
 * stamp} and anything inside a scratch directory. One rule for every side of a cached tree — the
 * fingerprint a key is computed from, the record the action cache stores, and the jar, zip or
 * layer a packager writes — so the bytes a key names are exactly the bytes it was computed over.
 *
 * <p>A scratch directory is a directory named {@code .jk-*}: a plugin's private bootstrap
 * repository or staging area, build bookkeeping rather than output. Only a <em>directory</em> is
 * scratch. A file whose own name starts with {@code .jk-} — a template's {@code .jk-template.toml}
 * — is content like any other, as are other dot-files ({@code .DS_Store} included): leaving one out
 * of a key while a packager ships it serves stale bytes for that key.
 *
 * <p>Lives on the host leaf beside {@link BuildStamps} for the same reason: the archive writers
 * that must agree with the engine's keys run in forked plugin workers, which link nothing heavier.
 */
public final class TreeContent {

    /** The name prefix of a scratch directory. */
    public static final String SCRATCH_PREFIX = ".jk-";

    private TreeContent() {}

    /** One content file: its {@code /}-separated path under the walked root, the file and its attributes. */
    @FunctionalInterface
    public interface Visit {
        void accept(String rel, Path file, BasicFileAttributes attrs) throws IOException;
    }

    /** Every content file under {@code root}, in walk order. An absent root has none. */
    public static void forEach(Path root, Visit visit) throws IOException {
        forEach(root, dir -> false, visit);
    }

    /** As {@link #forEach(Path, Visit)}, also leaving out any subdirectory {@code skip} accepts. */
    public static void forEach(Path root, Predicate<Path> skip, Visit visit) throws IOException {
        PathUtil.forEachRegularFile(root, dir -> isScratchDir(dir) || skip.test(dir), (file, attrs) -> {
            String rel = root.relativize(file).toString().replace(File.separatorChar, '/');
            if (BuildStamps.isStampFile(rel)) return;
            visit.accept(rel, file, attrs);
        });
    }

    /** True when the file at tree-relative {@code rel} ({@code /} or the platform separator) is content. */
    public static boolean isContent(String rel) {
        return !BuildStamps.isStampFile(rel.replace('\\', '/')) && !inScratchDir(rel);
    }

    /** As {@link #isContent(String)} for a tree-relative path. */
    public static boolean isContent(Path rel) {
        return isContent(rel.toString());
    }

    /** True when a directory on {@code rel}'s path, not counting its own file name, is a scratch directory. */
    public static boolean inScratchDir(String rel) {
        String slashed = rel.replace('\\', '/');
        int start = 0;
        for (int slash = slashed.indexOf('/'); slash >= 0; slash = slashed.indexOf('/', start)) {
            if (slashed.startsWith(SCRATCH_PREFIX, start)) return true;
            start = slash + 1;
        }
        return false;
    }

    /** True when {@code dir}, a directory, is a scratch directory by its name. */
    public static boolean isScratchDir(Path dir) {
        Path name = dir.getFileName();
        return name != null && name.toString().startsWith(SCRATCH_PREFIX);
    }
}
