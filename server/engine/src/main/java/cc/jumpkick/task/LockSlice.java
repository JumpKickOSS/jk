// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.lock.MemberRows;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The part of a workspace's one {@code jk-lock.toml} a module's outputs are built from: the rows its
 * classpaths resolve (its own roots' closure and what its siblings pass on, read as the member reads
 * the lock), with every other section kept and the writer's stamps left out. A module's outputs record
 * named the whole file, so a relock that moved one member's dependency, or only the jk that wrote the
 * lock, made every module's outputs "built from other inputs" and left that verdict standing until a
 * {@code jk build} (hotel-reservations, six modules, after a relock and a green {@code jk
 * test -g}).
 */
public final class LockSlice {

    private LockSlice() {}

    /**
     * The scopes whose rows reach a module's outputs: its compiles (main, provided, processors, the
     * test and fixture compiles), the jars it packages and the plugins its build runs. Not the dev
     * loop's, which no output carries.
     */
    static final Set<Scope> OUTPUT_SCOPES = Collections.unmodifiableSet(EnumSet.of(
            Scope.EXPORT,
            Scope.MAIN,
            Scope.PROVIDED,
            Scope.RUNTIME,
            Scope.TEST,
            Scope.PROCESSOR,
            Scope.TEST_PROCESSOR,
            Scope.PLUGIN));

    /**
     * A digest of {@code lockFile} as {@code module} at {@code moduleDir} builds its outputs from it;
     * {@code missing} without a lock, and the file's content hash for a module whose manifest did
     * not parse or a lock that will not read, as before.
     */
    public static String outputsDigest(Path moduleDir, @Nullable JkBuild module, Path lockFile) throws IOException {
        if (!Files.isRegularFile(lockFile)) return "missing";
        if (module == null) return FileHashMemo.contentHash(lockFile);
        Lockfile lock;
        try {
            lock = MemberRows.view(LockfileReader.read(lockFile), lockFile, moduleDir);
        } catch (IOException | RuntimeException unreadable) {
            return FileHashMemo.contentHash(lockFile);
        }
        Lockfile slice = new Lockfile(
                lock.version(),
                "",
                lock.resolutionAlgorithm(),
                lock.jdk(),
                lock.graal(),
                lock.kotlin(),
                lock.scala(),
                ClasspathResolver.moduleRows(lock, OUTPUT_SCOPES, module, moduleDir),
                lock.plugins(),
                lock.sdk(),
                lock.modules(),
                null,
                null,
                lock.projectId(),
                lock.nativeMetadata(),
                null,
                lock.node(),
                lock.tools(),
                lock.schemas(),
                null);
        return "slice:" + Hashing.sha256Hex(LockfileWriter.render(slice));
    }
}
