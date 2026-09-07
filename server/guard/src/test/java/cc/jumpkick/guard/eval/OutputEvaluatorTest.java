// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.rules.GuardRules;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.guard.rules.LoadResult;
import cc.jumpkick.guard.schema.Lane;
import cc.jumpkick.model.GuardsConfig;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OutputEvaluatorTest {

    /** A root with one member; the artefacts sit where the layout says they do. */
    static OutputArtifacts.Module scaffold(Path root) throws IOException {
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.acme"
                name = "ws"
                version = "1.0.0"
                jdk = 25
                [workspace]
                modules = ["core"]
                """);
        Files.createDirectories(root.resolve("core"));
        Files.writeString(
                root.resolve("core/jk.toml"), "group = \"com.acme\"\nname = \"core\"\nversion = \"1.0.0\"\njdk = 25\n");
        List<OutputArtifacts.Module> mods = OutputArtifacts.of(root, List.of(root.resolve("core")), null);
        assertThat(mods).hasSize(1);
        return mods.get(0);
    }

    static void jar(Path at, Map<String, String> manifestAttrs, String... entries) throws IOException {
        Files.createDirectories(Objects.requireNonNull(at.getParent()));
        Manifest mf = new Manifest();
        mf.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifestAttrs.forEach((k, v) -> mf.getMainAttributes().putValue(k, v));
        try (OutputStream os = Files.newOutputStream(at);
                JarOutputStream jar = new JarOutputStream(os, mf)) {
            for (String e : entries) {
                jar.putNextEntry(new JarEntry(e));
                jar.write(("content of " + e).getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
        }
    }

    static void pom(Path at, String group, String artifact, String version, String... deps) throws IOException {
        Files.createDirectories(Objects.requireNonNull(at.getParent()));
        StringBuilder sb = new StringBuilder("<project><modelVersion>4.0.0</modelVersion><groupId>")
                .append(group)
                .append("</groupId><artifactId>")
                .append(artifact)
                .append("</artifactId><version>")
                .append(version)
                .append("</version><dependencies>");
        for (String d : deps) {
            String[] gav = d.split(":");
            sb.append("<dependency><groupId>")
                    .append(gav[0])
                    .append("</groupId><artifactId>")
                    .append(gav[1])
                    .append("</artifactId><version>")
                    .append(gav[2])
                    .append("</version></dependency>");
        }
        Files.writeString(at, sb.append("</dependencies></project>").toString());
    }

    static Map<String, Evaluation> run(Path root, String rules, GuardsConfig config) throws Exception {
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), rules);
        LoadResult load = GuardRules.load(root, config);
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();
        EvalContext ctx = new EvalContext(
                        Lane.OUTPUT,
                        root,
                        "",
                        null,
                        List.of(root.resolve("core")),
                        () -> FactsIndex.EMPTY,
                        () -> null,
                        List::of)
                .withRules(load.rules());
        return LaneRun.evaluate(LaneRun.rulesFor(Lane.OUTPUT, load.rules(), ""), ctx);
    }

    static Evaluation ev(Map<String, Evaluation> r, String id) {
        return Objects.requireNonNull(r.get(id), id);
    }

    @Test
    void a_pom_naming_unspecified_or_a_foreign_group_fires(@TempDir Path root) throws Exception {
        OutputArtifacts.Module m = scaffold(root);
        pom(m.pom(), "com.acme", "core", "unspecified", "com.acme:ghost:1.0.0", "org.junit:junit:5");
        Map<String, Evaluation> r = run(root, """
                [guards.published-poms]
                kind = "output"
                pom  = { no-unspecified = true, groups = ["com.acme"] }
                why  = "a POM with an unspecified coordinate is an artifact nobody can depend on"
                """, GuardsConfig.ABSENT);
        Evaluation e = ev(r, "published-poms");
        assertThat(e.outcome()).isEqualTo(Outcome.VIOLATIONS);
        assertThat(e.observations().stream().map(Observation::detail))
                .anySatisfy(d -> assertThat(d).contains("version is `unspecified`"))
                .anySatisfy(d -> assertThat(d).contains("com.acme:ghost").contains("not an artifact it packages"));
        assertThat(e.observations()).hasSize(2);
        assertThat(e.population()).containsEntry("poms", 1L);
        assertThat(e.observations().get(0).file()).isEqualTo("target/core/lib/core-1.0.0.pom");
    }

    @Test
    void jar_entries_and_manifest_attributes_are_checked(@TempDir Path root) throws Exception {
        OutputArtifacts.Module m = scaffold(root);
        jar(m.jar(), Map.of("Automatic-Module-Name", "acme.core"), "com/acme/A.class", "com/acme/Util.kt");
        Map<String, Evaluation> r = run(root, """
                [guards.jar-shape]
                kind = "output"
                jar  = { forbid-entries = ["**/*.kt"], require-entries = ["META-INF/LICENSE", "META-INF/MANIFEST.MF"], manifest = { Automatic-Module-Name = "acme.core", Main-Class = "com.acme.Main" } }
                why  = "a jar is a contract"
                [guards.clean-jar]
                kind = "output"
                jar  = { require-entries = ["com/acme/*.class"] }
                why  = "w"
                """, GuardsConfig.ABSENT);
        Evaluation e = ev(r, "jar-shape");
        assertThat(e.outcome()).isEqualTo(Outcome.VIOLATIONS);
        assertThat(e.observations().stream().map(Observation::detail))
                .anySatisfy(d -> assertThat(d).contains("com/acme/Util.kt").contains("forbid-entries"))
                .anySatisfy(d -> assertThat(d).contains("no entry matches require-entries `META-INF/LICENSE`"))
                .anySatisfy(d -> assertThat(d).contains("no `Main-Class` attribute"));
        assertThat(e.observations()).hasSize(3);
        assertThat(ev(r, "clean-jar").outcome()).isEqualTo(Outcome.CLEAN);
        assertThat(ev(r, "clean-jar").bites()).isTrue();
    }

    @Test
    void no_artefact_is_not_evaluated_and_an_allow_excuses_a_module(@TempDir Path root) throws Exception {
        OutputArtifacts.Module m = scaffold(root);
        Map<String, Evaluation> none = run(root, """
                [guards.jar-shape]
                kind = "output"
                jar  = { forbid-entries = ["**/*.kt"] }
                why  = "w"
                """, GuardsConfig.ABSENT);
        Evaluation e = ev(none, "jar-shape");
        assertThat(e.outcome()).isEqualTo(Outcome.NOT_EVALUATED);
        assertThat(e.note()).contains("no artefact this build").contains("run jk build");
        jar(m.jar(), Map.of(), "x/Y.kt");
        Map<String, Evaluation> allowed = run(root, """
                [guards.jar-shape]
                kind  = "output"
                jar   = { forbid-entries = ["**/*.kt"] }
                allow = [{ in = "core", reason = "kotlin sources ship on purpose" }]
                why   = "w"
                [guards.stale]
                kind  = "output"
                jar   = { forbid-entries = ["**/*.kt"] }
                allow = [{ in = "nowhere", reason = "gone" }]
                why   = "w"
                """, GuardsConfig.ABSENT);
        assertThat(ev(allowed, "jar-shape").outcome()).isEqualTo(Outcome.CLEAN);
        assertThat(ev(allowed, "stale").outcome()).isEqualTo(Outcome.STALE_ALLOW);
    }
}
