// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;

/** The jars a plugin worker puts on the classpath of a tool it forks. */
public final class ToolJars {

    private ToolJars() {}

    /**
     * A fetched tool as a classpath: the jar itself, or every {@code .jar} under the directory of
     * its materialized closure, in path order so two machines holding the same jars fork the same
     * classpath.
     */
    public static List<Path> classpath(Path tool) throws IOException {
        if (!Files.isDirectory(tool)) return List.of(tool);
        List<Path> jars = new ArrayList<>();
        PathUtil.forEachRegularFile(tool, (file, attrs) -> {
            if (file.getFileName().toString().endsWith(".jar")) jars.add(file);
        });
        jars.sort(null);
        return List.copyOf(jars);
    }

    /**
     * The classpath for a fork of {@code main}, a worker's own command-line class: the jar (or
     * classes directory) that holds it and this library's, which a worker jar does not carry —
     * the host helpers such a class runs on reach the worker as a dependency of its POM.
     */
    public static List<Path> forkClasspath(Class<?> main) {
        Path own = codeSource(main);
        Path host = codeSource(ToolJars.class);
        return own.equals(host) ? List.of(own) : List.of(own, host);
    }

    /**
     * Where {@code type} was loaded from: its jar, or a classes directory when the worker runs from
     * a workspace build's own output. Either is a classpath entry for a fork.
     */
    public static Path codeSource(Class<?> type) {
        CodeSource source = type.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            throw new IllegalStateException("cannot name the jar that holds " + type.getName());
        }
        try {
            return Path.of(source.getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(
                    "the jar that holds " + type.getName() + " has no path: " + source.getLocation(), e);
        }
    }

    /**
     * {@code jars} for a tool that judges an input by its extension (d8, R8): a {@code .jar} or
     * {@code .zip} passes through, and any other gets a {@code .jar}-named link or copy in {@code
     * aliasDir}, created when the first one is needed.
     */
    public static List<Path> jarSuffixed(Path aliasDir, List<Path> jars) throws IOException {
        List<Path> out = new ArrayList<>(jars.size());
        int i = 0;
        for (Path jar : jars) {
            String name = jar.getFileName().toString();
            if (name.endsWith(".jar") || name.endsWith(".zip")) {
                out.add(jar);
            } else {
                Path alias = Files.createDirectories(aliasDir).resolve("rt-" + i + "-" + name + ".jar");
                Linking.linkOrCopy(jar, alias);
                out.add(alias);
            }
            i++;
        }
        return out;
    }
}
