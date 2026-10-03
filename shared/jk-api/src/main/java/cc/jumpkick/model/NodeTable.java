// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The {@code [node]} table: how a node build runs. Every key is optional; an unset one is inferred
 * from {@code package.json} and the lockfile ({@code NodeProject.infer}).
 *
 * @param packageManager {@code npm}, {@code pnpm}, {@code yarn} or {@code bun}
 * @param framework a framework id from the inference matrix ({@code vite}, {@code next}, …, {@code plain})
 * @param install the install command's argv, replacing the frozen install jk would run
 * @param build what {@code jk build} runs: a {@code package.json} script, or an {@code npx} / {@code exec} command
 * @param test the script the fast test tier runs
 * @param dev the script {@code jk dev} runs
 * @param start the argv that runs a server output under node
 * @param out the build output directory, relative to {@link #dir}
 * @param classpathRoot the root the output is packaged under in a jar
 * @param webappRoot the path the output is packaged under in a war
 * @param envPrefixes environment variable prefixes hashed into the build key; {@code null} is the framework's
 * @param devPort the port the dev server listens on
 * @param dir the directory holding {@code package.json}, relative to the module
 * @param skip {@code true}: no node step of the module runs
 * @param steps extra steps, in declaration order
 * @param exports named outputs a sibling module may consume, name → path relative to {@link #dir}
 */
public record NodeTable(
        @Nullable String packageManager,
        @Nullable String framework,
        @Nullable String install,
        @Nullable Command build,
        @Nullable String test,
        @Nullable String dev,
        @Nullable String start,
        @Nullable String out,
        @Nullable String classpathRoot,
        @Nullable String webappRoot,
        @Nullable List<String> envPrefixes,
        @Nullable Integer devPort,
        @Nullable String dir,
        boolean skip,
        List<Step> steps,
        Map<String, String> exports) {

    /** The table with no key set. */
    public static final NodeTable EMPTY = new NodeTable(
            null, null, null, null, null, null, null, null, null, null, null, null, null, false, List.of(), Map.of());

    /** The node build directory of a JVM module that holds one beside its sources. */
    public static final String SIDE_BY_SIDE_DIR = "src/main/node";

    public NodeTable {
        envPrefixes = envPrefixes == null ? null : List.copyOf(envPrefixes);
        steps = steps == null ? List.of() : List.copyOf(steps);
        exports = exports == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(exports));
    }

    /** What a command runs: a {@code package.json} script, a package binary through npx, or a binary on the path. */
    public record Command(Kind kind, String value) {

        /** How {@link #value} is run. */
        public enum Kind {
            /** A {@code package.json} script, through the module's package manager. */
            RUN,
            /** A package's binary, resolved from {@code node_modules} first, through the manager's npx. */
            NPX,
            /** A binary on the provisioned {@code PATH}. */
            EXEC;

            /** The manifest key that names this kind. */
            public String key() {
                return name().toLowerCase(Locale.ROOT);
            }
        }

        public Command {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(value, "value");
        }

        /** A {@code package.json} script. */
        public static Command run(String script) {
            return new Command(Kind.RUN, script);
        }

        /** A package binary through npx. */
        public static Command npx(String argv) {
            return new Command(Kind.NPX, argv);
        }
    }

    /** The node step a {@link Step} runs ahead of. */
    public enum Before {
        BUILD,
        TEST,
        PACKAGE
    }

    /** The tier a {@link Step} belongs to: the build, or the fast test tier ({@code --skip-tests} skips it). */
    public enum Tier {
        BUILD,
        TEST
    }

    /**
     * One {@code [[node.steps]]} entry.
     *
     * @param inputs module-relative globs beyond the node build tree that the step reads
     * @param outputs paths relative to {@link #dir} the step writes, cached and restored
     * @param allowUnlocked {@code true}: an {@code npx} package absent from the lockfile may be fetched
     */
    public record Step(
            String name,
            Command command,
            Before before,
            Tier tier,
            List<String> inputs,
            List<String> outputs,
            boolean allowUnlocked) {

        public Step {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(command, "command");
            before = before == null ? Before.BUILD : before;
            tier = tier == null ? Tier.BUILD : tier;
            inputs = inputs == null ? List.of() : List.copyOf(inputs);
            outputs = outputs == null ? List.of() : List.copyOf(outputs);
        }
    }
}
