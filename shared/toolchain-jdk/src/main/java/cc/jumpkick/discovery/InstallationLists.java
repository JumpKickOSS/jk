// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.discovery;

import cc.jumpkick.jdk.IntellijJdkDir;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * JDK homes a user typed into a list: Gradle's {@code org.gradle.java.installations.*} properties,
 * and jk's {@code JK_JDK_PATHS} / {@code JK_JDK_FROM_ENV}. Each list is comma-separated; a path is
 * taken as the home itself and never descended into.
 */
final class InstallationLists {

    static final String PATHS = "org.gradle.java.installations.paths";
    static final String FROM_ENV = "org.gradle.java.installations.fromEnv";
    static final String MAVEN_TOOLCHAINS_FILE = "org.gradle.java.installations.maven-toolchains-file";

    private InstallationLists() {}

    /** The homes {@code paths} names, then those the variables {@code fromEnv} names hold. */
    static List<Path> homes(@Nullable String paths, @Nullable String fromEnv, Function<String, @Nullable String> env) {
        List<Path> homes = new ArrayList<>();
        for (String path : split(paths)) add(homes, path);
        for (String name : split(fromEnv)) {
            String value = env.apply(name);
            if (value != null) add(homes, value);
        }
        return homes;
    }

    /**
     * The homes a {@code gradle.properties} names through {@code paths}, {@code fromEnv}, and the
     * Maven toolchains file it points at. A missing or unreadable file names none.
     */
    static List<Path> fromGradleProperties(Path file, Function<String, @Nullable String> env, Consumer<String> warn) {
        if (!Files.isRegularFile(file)) return List.of();
        Properties props = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) {
            props.load(in);
        } catch (IOException | RuntimeException e) {
            warn.accept("ignoring " + file + ": not a readable properties file (" + e.getMessage() + ")");
            return List.of();
        }
        List<Path> homes = new ArrayList<>(homes(props.getProperty(PATHS), props.getProperty(FROM_ENV), env));
        String toolchains = props.getProperty(MAVEN_TOOLCHAINS_FILE);
        if (toolchains != null && !toolchains.isBlank()) {
            try {
                homes.addAll(MavenToolchains.jdkHomes(Path.of(toolchains.strip()), env, warn));
            } catch (RuntimeException notAPathHere) {
                warn.accept("ignoring " + MAVEN_TOOLCHAINS_FILE + " in " + file + ": not a path");
            }
        }
        return homes;
    }

    private static List<String> split(@Nullable String list) {
        if (list == null || list.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String entry : list.split(",")) {
            String trimmed = entry.strip();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }

    private static void add(List<Path> homes, String value) {
        if (value.isBlank()) return;
        try {
            homes.add(IntellijJdkDir.javaHome(Path.of(value.strip())));
        } catch (RuntimeException notAPathHere) {
            // A value that is not a path on this OS names no JDK here.
        }
    }
}
