// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.script.ScriptHeader;
import cc.jumpkick.task.ActionKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Objects;

/**
 * What a {@code jk tool run} script's compiled classes are keyed by: the script's bytes, the
 * content of every {@code //SOURCES} file it compiles with, the {@code --with} dependencies joined
 * to its own, and the identity of the JDK that compiles it. A change to any of them compiles anew
 * instead of running classes built from the old one.
 */
final class ScriptCacheKey {

    /** The file an entry's last use is stamped on, which the cache retention reads as its age. */
    static final String USED = ".used";

    private ScriptCacheKey() {}

    /** The key of {@code script} (whose bytes are {@code bytes}) under {@code header} and {@code extraDeps}. */
    static String of(Path script, byte[] bytes, ScriptHeader header, List<Dependency> extraDeps) throws IOException {
        Path dir = Objects.requireNonNull(script.toAbsolutePath().getParent(), "script dir");
        StringBuilder key =
                new StringBuilder("script:").append(Hashing.sha256Hex(bytes)).append('\n');
        for (String source : header.sources()) {
            Path file = dir.resolve(source).normalize();
            key.append("source:")
                    .append(source)
                    .append('=')
                    .append(Files.isRegularFile(file) ? Hashing.sha256Hex(file) : "missing")
                    .append('\n');
        }
        for (Dependency dep : extraDeps) {
            key.append("with:").append(dep.module()).append(':').append(dep.version());
            if (dep.classifier() != null) key.append(':').append(dep.classifier());
            key.append('\n');
        }
        // The JDK the compile forks on, and the engine's own feature release, which a script that
        // names no //JAVA release compiles for.
        key.append("jdk:")
                .append(ActionKey.jdkToken(JavaHomes.resolveJavaHome(dir)))
                .append('\n');
        key.append("release:").append(Runtime.version().feature()).append('\n');
        return Hashing.sha256Hex(key.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Stamp the entry holding {@code classesDir} as used now, so a script run every day is kept. */
    static void stampUse(Path classesDir) throws IOException {
        Path entry = Objects.requireNonNull(classesDir.getParent(), "script cache entry");
        Files.createDirectories(entry);
        Path used = entry.resolve(USED);
        if (!Files.exists(used)) Files.createFile(used);
        Files.setLastModifiedTime(used, FileTime.fromMillis(Clock.SYSTEM.millis()));
    }
}
