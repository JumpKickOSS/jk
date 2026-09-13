// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.tool;

import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.model.Coordinate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Reads an installed tool's {@code env.json} — what {@link ToolLauncher#install} wrote — back into
 * a runnable {@link ToolEnv}. This is how {@code jk tool run <name>} / {@code jkx <name>} honour
 * an install: the recorded coordinate, {@code Main-Class} and classpath are what a later run uses,
 * never re-asked from the user.
 */
public final class InstalledToolEnvs {

    private InstalledToolEnvs() {}

    /** An installed tool ready to exec: the env plus the JVM it was installed against. */
    public record Installed(ToolEnv env, @Nullable Path javaHome, List<String> jvmArgs) {}

    /**
     * The installed tool named {@code name} under {@code envsRoot}, or {@code null} when there is
     * none, its metadata is unreadable, it is a Kotlin-script tool (its launcher owns the exec
     * shape), or any recorded classpath entry is gone (the caller should fall through to a fresh
     * resolve, which re-fetches).
     */
    /**
     * The classpath entries {@code name}'s {@code env.json} records, present on disk or not — the
     * paths its launcher execs, and so the roots whose deletion orphans that launcher. Empty when
     * there is no readable env.
     */
    public static List<Path> recordedClasspath(Path envsRoot, String name) {
        Path envJson = envsRoot.resolve(name).resolve("env.json");
        if (!Files.isRegularFile(envJson)) return List.of();
        try {
            Object root = MiniJson.parse(Files.readString(envJson));
            List<Path> classpath = new ArrayList<>();
            for (Object entry : MiniJson.list(root, "classpath")) {
                if (entry instanceof String s) classpath.add(Path.of(s));
            }
            return List.copyOf(classpath);
        } catch (Exception unreadable) {
            return List.of();
        }
    }

    public static @Nullable Installed read(Path envsRoot, String name) {
        Path envJson = envsRoot.resolve(name).resolve("env.json");
        if (!Files.isRegularFile(envJson)) return null;
        try {
            Object root = MiniJson.parse(Files.readString(envJson));
            String primary = MiniJson.str(root, "primary");
            String mainClass = MiniJson.str(root, "mainClass");
            if (primary == null || mainClass == null || "kotlin-script".equals(mainClass)) return null;
            List<Path> classpath = new ArrayList<>();
            for (Object entry : MiniJson.list(root, "classpath")) {
                if (!(entry instanceof String s)) return null;
                Path p = Path.of(s);
                if (!Files.exists(p)) return null;
                classpath.add(p);
            }
            List<String> jvmArgs = new ArrayList<>();
            for (Object arg : MiniJson.list(root, "jvmArgs")) {
                if (arg instanceof String s) jvmArgs.add(s);
            }
            String javaHome = MiniJson.str(root, "javaHome");
            return new Installed(
                    new ToolEnv(name, Coordinate.parse(primary), mainClass, List.copyOf(classpath)),
                    javaHome != null ? Path.of(javaHome) : null,
                    List.copyOf(jvmArgs));
        } catch (Exception unreadable) {
            return null;
        }
    }
}
