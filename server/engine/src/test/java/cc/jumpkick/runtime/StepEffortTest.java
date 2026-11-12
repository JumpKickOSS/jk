// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A step kind a module has no walls of is priced from one cheap measure of its effort (JK-PMF9X). */
class StepEffortTest {

    @TempDir
    Path tmp;

    private static StepEffort.Price price(StepEffort e, String step, StepEffort.Measure m, double scale) {
        return Objects.requireNonNull(e.price(step, m, scale), step);
    }

    private Path file() {
        return tmp.resolve(StepEffort.FILE);
    }

    @Test
    void a_node_install_is_measured_by_the_packages_its_lockfile_pins() throws Exception {
        Path npm = Files.createDirectories(tmp.resolve("npm"));
        Files.writeString(npm.resolve("package-lock.json"), """
                {
                  "packages": {
                    "": { "name": "app" },
                    "node_modules/a": { "version": "1.0.0" },
                    "node_modules/b": { "version": "1.0.0" },
                    "node_modules/b/node_modules/c": { "version": "2.0.0" }
                  }
                }
                """);
        Path pnpm = Files.createDirectories(tmp.resolve("pnpm"));
        Files.writeString(pnpm.resolve("pnpm-lock.yaml"), """
                lockfileVersion: '9.0'
                importers:
                  .:
                    dependencies: {}
                packages:
                  a@1.0.0:
                    resolution: {integrity: x}
                  b@2.0.0:
                    resolution: {integrity: y}
                snapshots:
                  a@1.0.0: {}
                """);
        Path yarn = Files.createDirectories(tmp.resolve("yarn"));
        Files.writeString(
                yarn.resolve("yarn.lock"),
                "# yarn lockfile v1\n\na@^1:\n  version \"1.0.0\"\n\nb@^2:\n  version \"2.0.0\"\n");
        Path nested = Files.createDirectories(npm.resolve("web"));

        assertThat(StepEffort.lockPackages(npm)).isEqualTo(3);
        assertThat(StepEffort.lockPackages(pnpm)).isEqualTo(2);
        assertThat(StepEffort.lockPackages(yarn)).isEqualTo(2);
        assertThat(StepEffort.lockPackages(nested))
                .as("the nearest lockfile above the module")
                .isEqualTo(3);
    }

    @Test
    void a_node_build_or_test_is_measured_by_the_tool_its_script_runs() throws Exception {
        Files.writeString(tmp.resolve("package.json"), """
                {"name": "web", "scripts": {"build": "tsc -b && vite build", "test": "npm run check", "lint": "eslint ."}}
                """);
        assertThat(StepEffort.scriptTool(tmp, "build")).isEqualTo("vite");
        assertThat(StepEffort.scriptTool(tmp, "test")).isEqualTo("npm-run-check");
        assertThat(StepEffort.scriptTool(tmp, "start")).isEmpty();
        assertThat(StepEffort.scriptTool(tmp.resolve("none"), "build")).isEmpty();
    }

    @Test
    void a_kind_the_host_never_ran_is_priced_from_the_cold_set_and_then_from_its_own_runs() {
        StepEffort none = StepEffort.load(file());
        StepEffort.Price ng = price(none, "node-build", new StepEffort.Measure("ng", 0), 1.0);
        assertThat(ng.learned()).isFalse();
        assertThat(ng.ms()).isEqualTo(StepEffort.REF_BUILD_MS.get("ng"));
        assertThat(price(none, "node-install", new StepEffort.Measure("", 400), 2.0)
                        .ms())
                .as("the reference per-package rate at the host's CPU scale")
                .isEqualTo(Math.round(StepEffort.REF_MS_PER_PACKAGE * 400 * 2.0));
        assertThat(price(none, "plugin-spring-aot", StepEffort.Measure.NONE, 1.0)
                        .ms())
                .isEqualTo(3_500);
        assertThat(none.price("compile-java", StepEffort.Measure.NONE, 1.0))
                .as("not a kind this prices")
                .isNull();

        StepEffort.learn(
                file(),
                List.of(
                        new StepEffort.Sample("node-build", new StepEffort.Measure("ng", 0), 3_000),
                        new StepEffort.Sample("node-install", new StepEffort.Measure("", 100), 1_000),
                        new StepEffort.Sample("plugin-generate-jooq", StepEffort.Measure.NONE, 2_000),
                        new StepEffort.Sample("compile-java", StepEffort.Measure.NONE, 9_000)));
        StepEffort host = StepEffort.load(file());

        assertThat(price(host, "node-build", new StepEffort.Measure("ng", 0), 1.0))
                .isEqualTo(new StepEffort.Price(3_000, true));
        assertThat(price(host, "node-build", new StepEffort.Measure("vite", 0), 1.0)
                        .ms())
                .as("a tool the cold set priced keeps its price over another tool's runs here")
                .isEqualTo(StepEffort.REF_BUILD_MS.get("vite"));
        assertThat(price(host, "node-build", new StepEffort.Measure("rspack", 0), 1.0))
                .as("a tool nobody priced: the kind's runs here")
                .isEqualTo(new StepEffort.Price(3_000, true));
        assertThat(price(host, "node-install", new StepEffort.Measure("", 400), 1.0))
                .as("this host's per-package rate: 10 ms")
                .isEqualTo(new StepEffort.Price(4_000, true));
        assertThat(price(host, "plugin-generate-jooq", StepEffort.Measure.NONE, 1.0))
                .isEqualTo(new StepEffort.Price(2_000, true));
    }

    @Test
    void only_the_latest_runs_of_each_kind_are_kept() throws Exception {
        for (int i = 1; i <= StepEffort.KEEP + 4; i++) {
            StepEffort.learn(file(), List.of(new StepEffort.Sample("plugin-x", StepEffort.Measure.NONE, i * 100L)));
        }
        long lines = Files.readAllLines(file()).stream()
                .filter(l -> l.startsWith("plugin-x"))
                .count();
        assertThat(lines).isEqualTo(StepEffort.KEEP);
        List<Long> walls = new ArrayList<>();
        for (int i = 5; i <= StepEffort.KEEP + 4; i++) walls.add(i * 100L);
        walls.sort(null);
        long median = (walls.get(7) + walls.get(8)) / 2;
        assertThat(price(StepEffort.load(file()), "plugin-x", StepEffort.Measure.NONE, 1.0)
                        .ms())
                .isEqualTo(median);
    }
}
