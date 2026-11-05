// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.discovery;

import cc.jumpkick.config.ConfigReference;
import cc.jumpkick.util.MinimalXml;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Reads the JDK homes a Maven {@code toolchains.xml} lists: every {@code <toolchain>} of type
 * {@code jdk}, its {@code <configuration><jdkHome>}, with {@code ${env.NAME}} expanded from the
 * environment. A home naming an unset variable is left out.
 */
public final class MavenToolchains {

    private static final String ENV_PREFIX = "env.";

    private MavenToolchains() {}

    /**
     * The homes {@code file} lists, in file order. A missing file is empty; a file that does not
     * parse is reported to {@code warn} and read as empty.
     */
    public static List<Path> jdkHomes(Path file, Function<String, @Nullable String> env, Consumer<String> warn) {
        if (!Files.isRegularFile(file)) return List.of();
        MinimalXml.Element root;
        try {
            root = MinimalXml.parse(Files.readString(file));
        } catch (IOException | RuntimeException e) {
            warn.accept("ignoring " + file + ": not a readable toolchains.xml (" + e.getMessage() + ")");
            return List.of();
        }
        List<Path> homes = new ArrayList<>();
        for (MinimalXml.Element toolchain : root.elements("toolchain")) {
            String type =
                    toolchain.element("type").map(MinimalXml.Element::text).orElse("");
            if (!"jdk".equals(type)) continue;
            String raw = toolchain
                    .element("configuration")
                    .flatMap(c -> c.element("jdkHome"))
                    .map(MinimalXml.Element::text)
                    .orElse("");
            String expanded = expand(raw, env);
            if (expanded == null || expanded.isBlank()) continue;
            try {
                homes.add(Path.of(expanded));
            } catch (RuntimeException notAPathHere) {
                // A value that is not a path on this OS names no JDK here.
            }
        }
        return homes;
    }

    /**
     * {@code raw} with every {@code ${env.NAME}} replaced, or null when a named variable is unset.
     * Read through {@link ConfigReference}'s Maven grammar, the one {@code settings.xml} uses; an
     * expression other than {@code env.NAME} stays as written.
     */
    static @Nullable String expand(String raw, Function<String, @Nullable String> env) {
        boolean[] unset = {false};
        String expanded = ConfigReference.expand(raw, ConfigReference::mavenExpression, expression -> {
            if (!expression.startsWith(ENV_PREFIX)) return null;
            String value = env.apply(expression.substring(ENV_PREFIX.length()));
            if (value == null || value.isEmpty()) unset[0] = true;
            return value;
        });
        return unset[0] ? null : expanded;
    }
}
