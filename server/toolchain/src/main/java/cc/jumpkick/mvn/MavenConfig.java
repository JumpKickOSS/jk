// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * The {@code -D} options of a build's {@code .mvn/maven.config}, which Maven reads as if they were
 * on its command line: they are user properties, so they fill {@code ${revision}} and every other
 * placeholder ahead of the POM's own {@code <properties>}. The file sits in the {@code .mvn}
 * directory of the nearest directory at or above the POM's that has one, the one Maven's launcher
 * takes as the project root.
 */
final class MavenConfig {

    /** The config's path below the project root. */
    static final String PATH = ".mvn/maven.config";

    /** Splits a line holding several options; Maven 3.9 and later want one per line, older ones any spacing. */
    private static final Pattern OPTIONS = Pattern.compile("\\s+(?=-)");

    private MavenConfig() {}

    /** The config Maven reads for a build of {@code pom}, whether or not the file exists. */
    static Path file(Path pom) {
        Path dir = Objects.requireNonNull(pom.toAbsolutePath().normalize().getParent(), "pom directory");
        for (Path at = dir; at != null; at = at.getParent()) {
            if (Files.isDirectory(at.resolve(".mvn"))) return at.resolve(PATH);
        }
        return dir.resolve(PATH);
    }

    /** The user properties the config of {@code pom}'s build defines; empty with no readable file. */
    static Properties properties(Path pom) {
        Path config = file(pom);
        if (!Files.isRegularFile(config)) return new Properties();
        try {
            return parse(Files.readAllLines(config, StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            return new Properties();
        }
    }

    /**
     * The {@code -Dname=value} options of {@code lines}, also spelled {@code -D name=value} or
     * {@code --define name=value}; a bare {@code -Dname} is {@code true}, as on Maven's command line.
     * Blank lines, {@code #} comments and every other option are skipped.
     */
    static Properties parse(List<String> lines) {
        Properties props = new Properties();
        boolean defineNext = false;
        for (String line : lines) {
            String text = line.strip();
            if (text.isEmpty() || text.startsWith("#")) continue;
            for (String option : OPTIONS.split(text)) {
                if (defineNext) {
                    define(props, option);
                    defineNext = false;
                } else if (option.equals("-D") || option.equals("--define")) {
                    defineNext = true;
                } else if (option.startsWith("--define ") || option.startsWith("--define=")) {
                    define(props, option.substring("--define".length() + 1));
                } else if (option.startsWith("-D")) {
                    define(props, option.substring(2));
                }
            }
        }
        return props;
    }

    private static void define(Properties props, String definition) {
        String text = definition.strip();
        int eq = text.indexOf('=');
        String name = (eq < 0 ? text : text.substring(0, eq)).strip();
        if (name.isEmpty()) return;
        props.setProperty(name, eq < 0 ? "true" : text.substring(eq + 1).strip());
    }
}
