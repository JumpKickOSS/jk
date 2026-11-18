// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.Log;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The one line a module hears when its suites are first keyed on the caller's whole environment:
 * why an unrelated variable may run them again, and the {@code [env] scope} that narrows it, with
 * the prefixes of the framework that read it. Spring Boot, Quarkus, Micronaut, Flyway and
 * Testcontainers all list the environment and read every value, so a suite of theirs is keyed on
 * all of it; a scope keeps what a test sees, and so what keys it, to the names it lists.
 *
 * <p>Said once per module, kept beside what its suites were found to read: not on every run, not
 * once per suite, not in every verdict after. Never for a module that scopes, declares or is
 * strict: it has already said what its tests see.
 *
 * @param frameworks the frameworks the module's dependencies name, in the order the line names them
 * @param scope the prefixes those frameworks read, then the placeholder for the application's own
 */
public record EnvScopeHint(List<String> frameworks, List<String> scope) {

    /** The placeholder for the application's own prefix, as the line and the templates spell it. */
    public static final String YOUR_APP = "<YOURAPP>_*";

    /** A framework by the group its artifacts carry, and the prefixes it reads. */
    private record Framework(String name, String group, List<String> prefixes) {}

    private static final List<Framework> KNOWN = List.of(
            new Framework("Spring Boot", "org.springframework.boot", List.of("SPRING_*")),
            new Framework("Quarkus", "io.quarkus", List.of("QUARKUS_*")),
            new Framework("Micronaut", "io.micronaut", List.of("MICRONAUT_*")),
            new Framework("Helidon", "io.helidon", List.of()),
            new Framework("Flyway", "org.flywaydb", List.of("FLYWAY_*")),
            new Framework("Testcontainers", "org.testcontainers", List.of("TESTCONTAINERS_*", "DOCKER_*")));

    /** The hint for {@code project}: the frameworks its dependencies or plugin tables name. */
    public static EnvScopeHint of(JkBuild project) {
        Set<String> groups = new LinkedHashSet<>();
        project.dependencies().byScope().values().forEach(deps -> {
            for (Dependency d : deps) {
                int colon = d.module().indexOf(':');
                if (colon > 0) groups.add(d.module().substring(0, colon));
            }
        });
        List<String> frameworks = new ArrayList<>();
        Set<String> scope = new LinkedHashSet<>();
        for (Framework f : KNOWN) {
            boolean named = groups.stream().anyMatch(g -> g.equals(f.group()) || g.startsWith(f.group() + "."))
                    || (f.name().equals("Spring Boot") && project.isSpringBoot())
                    || (f.name().equals("Micronaut") && project.isMicronaut());
            if (!named) continue;
            frameworks.add(f.name());
            scope.addAll(f.prefixes());
        }
        scope.add(YOUR_APP);
        return new EnvScopeHint(frameworks, List.copyOf(scope));
    }

    /** The {@code [env]} line that narrows the module's suites, as jk.toml spells it. */
    public String scopeLine() {
        StringBuilder sb = new StringBuilder("[env] scope = [");
        for (int i = 0; i < scope.size(); i++)
            sb.append(i == 0 ? "" : ", ").append('"').append(scope.get(i)).append('"');
        return sb.append(']').toString();
    }

    /**
     * The line for a suite whose record is now {@code env}, keyed broadly under {@code policy}; null
     * when it is not broad, or the module scopes, declares or is strict.
     */
    public @Nullable String message(SuiteEnvReads.Record env, SuiteEnvReads.Policy policy) {
        if (!env.listed() && !env.whole()) return null;
        if (policy.strict() || !policy.scope().isEmpty() || !policy.declared().isEmpty()) return null;
        String who = env.whole() && !env.listed()
                ? "a test starts a program, which sees your whole environment"
                : frameworks.isEmpty()
                        ? "the tests read your whole environment"
                        : String.join(" and ", frameworks) + " reads your whole environment";
        return who + ", so changing any variable runs these tests again; " + scopeLine()
                + " in jk.toml keeps them to those names (and lets the result be shared)";
    }

    /** Whether the module at {@code moduleDir} has not heard the line yet; it has, once this returns true. */
    public static boolean firstFor(Path moduleDir) {
        Path said = SuiteReads.discoveredFile(moduleDir, TestSelection.DEFAULT).resolveSibling(SAID);
        if (Files.exists(said)) return false;
        try {
            Files.createDirectories(said.getParent());
            AtomicWrites.replace(said, "");
        } catch (IOException | RuntimeException unwritten) {
            Log.debug("env scope hint: not noted for " + moduleDir, unwritten);
        }
        return true;
    }

    private static final String SAID = "env-scope-hint-said";
}
