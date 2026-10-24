// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.ToolsGc;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.jdk.JdkInstaller;
import cc.jumpkick.task.FileHashMemo;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.util.ToolUse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * A step-dependency handed over unpacked ({@code unpack = true}): the fetched zip is extracted once
 * into scratch space ({@code JkDirs.tmp()}, {@code unpacked/<sha256 of the archive>/}) — a means to
 * an end, regenerable from the archive in the store, so it lives where everything is safe to delete —
 * staged and published atomically, so the directory is whole or absent and an unchanged archive is never unpacked again.
 * The directory's name is the archive's content digest, which is the tool's identity in an action
 * key ({@link #identity}): fingerprinting the unpacked tree would walk every file of it per build.
 */
final class ToolArchives {

    /** Written last into a whole directory; a directory without it is a torn unpack. */
    static final String COMPLETE = ".jk-unpacked";

    private ToolArchives() {}

    /** The unpacked directory of {@code archive}, extracted on first use. */
    static Path unpack(Path archive, List<String> include) throws IOException {
        return unpack(archive, include, JkDirs.tmp());
    }

    /**
     * The unpacked directory of {@code archive} under {@code scratch}, extracted on first use. A
     * non-empty {@code include} extracts only the entries its globs match, and names the directory by
     * them too: the same archive taken whole and taken in part are different trees.
     */
    static Path unpack(Path archive, List<String> include, Path scratch) throws IOException {
        Path dir = unpackInto(archive, include, scratch);
        ToolUse.record(scratch, ToolsGc.UNPACKED, dir);
        return dir;
    }

    private static Path unpackInto(Path archive, List<String> include, Path toolsRoot) throws IOException {
        String sha = FileHashMemo.contentHash(archive.toAbsolutePath().normalize());
        if (!include.isEmpty()) {
            sha += "-"
                    + Hashing.sha256Hex(String.join("\n", include).getBytes(StandardCharsets.UTF_8))
                            .substring(0, 12);
        }
        Path dir = toolsRoot.resolve(ToolsGc.UNPACKED).resolve(sha);
        if (Files.isRegularFile(dir.resolve(COMPLETE))) return dir;
        Path staging = Files.createTempDirectory(Files.createDirectories(dir.getParent()), ".unpack-");
        try {
            if (include.isEmpty()) JdkInstaller.extract(archive, staging, "zip");
            else JdkInstaller.extractZip(archive, staging, matching(include));
            Files.writeString(staging.resolve(COMPLETE), archive.getFileName() + "\n");
            if (Files.isDirectory(dir)) PathUtil.deleteRecursively(dir);
            AtomicWrites.publishDir(staging, dir);
        } catch (IOException e) {
            PathUtil.deleteRecursively(staging);
            if (Files.isRegularFile(dir.resolve(COMPLETE))) return dir;
            throw new IOException("unpacking " + archive + " into " + dir + " failed: " + e.getMessage(), e);
        }
        return dir;
    }

    /** Zip entry names that any of the {@code globs} (relative to the archive root) matches. */
    static Predicate<String> matching(List<String> globs) {
        List<PathMatcher> matchers = new ArrayList<>();
        for (String glob : globs) matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + glob));
        return name -> {
            String clean = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
            if (clean.isEmpty()) return false;
            Path entry = Path.of(clean);
            for (PathMatcher matcher : matchers) if (matcher.matches(entry)) return true;
            return false;
        };
    }

    /** True when {@code path} is a directory {@link #unpack} published. */
    static boolean isUnpacked(Path path) {
        return Files.isRegularFile(path.resolve(COMPLETE));
    }

    /** An unpacked tool's action-key identity: the archive digest its directory is named by. */
    static String identity(Path dir) {
        return "unpacked:" + dir.getFileName();
    }
}
