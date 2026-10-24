// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.ToolsGc;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.jdk.JdkInstaller;
import cc.jumpkick.task.FileHashMemo;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.util.ToolUse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
    static Path unpack(Path archive) throws IOException {
        return unpack(archive, JkDirs.tmp());
    }

    /** The unpacked directory of {@code archive} under {@code scratch}, extracted on first use. */
    static Path unpack(Path archive, Path scratch) throws IOException {
        Path dir = unpackInto(archive, scratch);
        ToolUse.record(scratch, ToolsGc.UNPACKED, dir);
        return dir;
    }

    private static Path unpackInto(Path archive, Path toolsRoot) throws IOException {
        String sha = FileHashMemo.contentHash(archive.toAbsolutePath().normalize());
        Path dir = toolsRoot.resolve(ToolsGc.UNPACKED).resolve(sha);
        if (Files.isRegularFile(dir.resolve(COMPLETE))) return dir;
        Path staging = Files.createTempDirectory(Files.createDirectories(dir.getParent()), ".unpack-");
        try {
            JdkInstaller.extract(archive, staging, "zip");
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

    /** True when {@code path} is a directory {@link #unpack} published. */
    static boolean isUnpacked(Path path) {
        return Files.isRegularFile(path.resolve(COMPLETE));
    }

    /** An unpacked tool's action-key identity: the archive digest its directory is named by. */
    static String identity(Path dir) {
        return "unpacked:" + dir.getFileName();
    }
}
