// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.protobuf;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.plugin.build.TaskExec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The {@code .proto} files the module's dependency jars carry, unpacked so protoc can import
 * them: {@code google/protobuf/*.proto} ride in protobuf-java, {@code google/rpc/status.proto}
 * and {@code google/api/*.proto} in proto-google-common-protos, and a library's own contract
 * often ships beside its classes. Each jar holding one becomes an include root under the step's
 * scratch, in classpath order after the module's own proto root — the runtime closure first, then
 * the jars only the compile classpath carries ({@code provided} contracts) — and a jar without
 * protos adds none.
 */
final class DependencyProtos {

    private DependencyProtos() {}

    /** The include roots, freshly unpacked under {@code scratch/includes}. */
    static List<Path> includeRoots(TaskExec exec) throws IOException {
        Path includes = exec.scratch().resolve("includes");
        PathUtil.deleteRecursivelyOrThrow(includes);
        List<Path> roots = new ArrayList<>();
        LinkedHashSet<Path> jars = new LinkedHashSet<>(exec.runtimeClasspath());
        jars.addAll(exec.compileClasspath());
        for (Path jar : jars) {
            String name = jar.getFileName().toString();
            if (!name.endsWith(".jar") || !Files.isRegularFile(jar)) continue;
            Path root = includes.resolve(name.substring(0, name.length() - ".jar".length()));
            for (int n = 2; roots.contains(root); n++) {
                root = includes.resolve(root.getFileName() + "-" + n);
            }
            if (unpackProtos(jar, root)) roots.add(root);
        }
        return roots;
    }

    /** Copy every {@code .proto} entry of {@code jar} under {@code root}; false when it has none. */
    private static boolean unpackProtos(Path jar, Path root) throws IOException {
        boolean any = false;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.endsWith(".proto") || name.startsWith("META-INF/")) continue;
                Path target = root.resolve(name).normalize();
                if (!target.startsWith(root)) continue; // an entry escaping its root is no import path
                Files.createDirectories(target.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                any = true;
            }
        }
        return any;
    }
}
