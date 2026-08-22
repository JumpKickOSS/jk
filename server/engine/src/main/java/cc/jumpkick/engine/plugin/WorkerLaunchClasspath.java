// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.repo.PomRuntimeClasspath;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Classpath used to fork a thin plugin worker: the worker jar plus the Maven runtime closure from
 * its POM ({@code repos/local} / {@code jumpkick} / {@code central}). A workspace {@code target/}
 * worker also gets plugin-sdk and jsonl from {@code target/shared/} (the codec Gradle vendors into
 * the jar).
 */
public final class WorkerLaunchClasspath {

    private WorkerLaunchClasspath() {}

    public static List<Path> paths(Path workerJar) {
        // A CAS blob can only reach a fork as a path-pinned plugin jar — coordinate pins and
        // first-party workers resolve to Maven-layout paths. Path pins carry no POM by design,
        // so the sha-verified jar is the whole classpath.
        if (Cas.isBlobPath(workerJar)) return List.of(workerJar);
        Path worker = workerJar.toAbsolutePath().normalize();
        List<Path> resolved = PomRuntimeClasspath.resolve(worker);
        List<Path> codec = workspaceCodec(worker);
        if (codec.isEmpty()) return resolved;
        // Codec dirs FIRST so a just-compiled classes/main wins over the copy the worker jar vendors
        // — first-match-wins otherwise let a stale vendored codec shadow the fresh SDK, the exact
        // codec skew this path exists to avoid (JK-2326).
        LinkedHashSet<Path> out = new LinkedHashSet<>();
        out.addAll(codec);
        out.addAll(resolved);
        return List.copyOf(out);
    }

    public static String resolve(Path workerJar) {
        String sep = System.getProperty("path.separator", ":");
        return paths(workerJar).stream().map(Path::toString).collect(Collectors.joining(sep));
    }

    /**
     * plugin-sdk + jsonl next to a workspace-built worker. Prefer {@code classes/main} when present
     * so a just-compiled SDK is used even if the sibling jar is stale.
     */
    static List<Path> workspaceCodec(Path workerJar) {
        Path target = workspaceTarget(workerJar);
        if (target == null) return List.of();
        List<Path> out = new ArrayList<>();
        addCodecModule(out, target.resolve("shared").resolve("plugin-sdk"), "jk-plugin-sdk-");
        addCodecModule(out, target.resolve("shared").resolve("jsonl"), "jk-jsonl-");
        return List.copyOf(out);
    }

    static Path workspaceTarget(Path workerJar) {
        Path cur = workerJar.toAbsolutePath().normalize().getParent();
        while (cur != null) {
            Path name = cur.getFileName();
            if (name != null && "target".equals(name.toString()) && Files.isDirectory(cur.resolve("shared"))) {
                return cur;
            }
            cur = cur.getParent();
        }
        return null;
    }

    private static void addCodecModule(List<Path> out, Path moduleTarget, String jarPrefix) {
        Path classes = moduleTarget.resolve("classes").resolve("main");
        if (Files.isDirectory(classes)) {
            out.add(classes);
            return;
        }
        Path lib = moduleTarget.resolve("lib");
        if (!Files.isDirectory(lib)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(lib, "*.jar")) {
            for (Path jar : stream) {
                String n = jar.getFileName().toString();
                if (n.startsWith(jarPrefix)) out.add(jar.toAbsolutePath().normalize());
            }
        } catch (IOException ignored) {
            // Launch still fails clearly if PluginMain is missing.
        }
    }
}
