// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.helidon;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.helidon.ArchetypeInputs.Input;
import cc.jumpkick.helidon.ArchetypeInputs.Snapshot;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The {@code helidon/*} templates' questions against the inputs of Helidon's own project archetypes
 * ({@code helidon init}), as the checked-in snapshot {@code archetype-inputs.txt} records them for the
 * Helidon release the templates pin. Every archetype input, and every option of the enums and lists
 * jk answers option by option, is either a template question or recorded here with the reason jk
 * does not ask it; every template question is either an archetype input's or recorded as jk's own.
 * An input upstream adds (seen when the snapshot is re-derived, by {@link HelidonArchetypeUpstreamTest}
 * or after a Helidon bump) fails here until it is mapped or explained.
 *
 * <p>Fast tier: classpath resources only.
 */
class HelidonArchetypeOptionsTest {

    /** The templates the archetype's inputs are diffed against, by catalogue id. */
    private static final List<String> TEMPLATES = List.of("helidon/quickstart", "helidon/declarative");

    /** Marks an archetype input jk answers option by option, in {@link #OPTIONS}. */
    private static final String BY_OPTION = "by option";

    /**
     * What jk does with each archetype input: {@code ask:<key>} is the template question that
     * answers it, {@link #BY_OPTION} sends it to {@link #OPTIONS}, and anything else is why no
     * template asks it.
     */
    private static final Map<String, String> INPUTS = Map.ofEntries(
            entry("flavor", "Helidon 27 ships SE only, and [helidon] is SE"),
            entry("app-type", BY_OPTION),
            entry("build-system", "jk is the build system"),
            entry("artifactId", "jk new's name"),
            entry("groupId", "jk new's --group"),
            entry("package", "jk new derives the package from the group"),
            entry("version", "the scaffold's version, 0.1.0"),
            entry("media", BY_OPTION),
            entry("media.json-lib", BY_OPTION),
            entry("metrics", "ask:metrics"),
            entry("metrics.builtin", "ask:metrics"),
            entry("health", "ask:health"),
            entry("health.builtin", "ask:health"),
            entry("tracing", "ask:tracing"),
            entry("tracing.provider", BY_OPTION),
            entry("docker", "ask:docker"),
            entry("docker.jlink-image", "ask:jlink"),
            entry("k8s", "ask:k8s"),
            entry("jpms", "jk new writes no module-info.java; Helidon's own default is the classpath"),
            entry("extra", "a dependency away: jk add helidon-webclient, helidon-fault-tolerance, helidon-cors"),
            entry("security", "the custom application type's; jk add the security provider"),
            entry("security.atn", "the custom application type's; jk add the security provider"),
            entry("security.atz", "the custom application type's; jk add the security provider"),
            entry("db", "the database application type's: helidon/database, a template still to come"),
            entry("db.server", "the database application type's: helidon/database, a template still to come"));

    /**
     * What jk does with each option of a {@link #BY_OPTION} input, keyed {@code <path>=<option>}:
     * {@code ask:<key>} or {@code ask:<key>=<option>} is the question (and answer) that gives it,
     * {@code template:<id>} the template that is it, and anything else is why jk does not offer it.
     */
    private static final Map<String, String> OPTIONS = Map.ofEntries(
            entry("app-type=quickstart", "template:helidon/quickstart"),
            entry("app-type=database", "helidon/database, a template still to come"),
            entry("app-type=custom", "the quickstart's questions, with jk add for the rest"),
            entry("media=json", "ask:media"),
            entry("media=multipart", "a dependency away: jk add helidon-http-media-multipart"),
            entry("media.json-lib=jsonp", "ask:media=jsonp"),
            entry("media.json-lib=jackson", "a dependency away: jk add helidon-http-media-jackson"),
            entry("media.json-lib=jsonb", "a dependency away: jk add helidon-http-media-jsonb"),
            entry("tracing.provider=opentelemetry", "ask:tracing"));

    /**
     * Template questions, and answers, that are jk's own rather than an archetype input's, keyed
     * {@code <key>} or {@code <key>=<option>}, each with where it comes from.
     */
    private static final Map<String, String> JK_ONLY = Map.ofEntries(
            entry(
                    "media=json",
                    "Helidon JSON (helidon-http-media-json), new in 27; the 27.0.1 archetype offers JSON-P"),
            entry("generate_main", "helidon-service-maven-plugin's generateMain, as [helidon] generate-main"));

    private static final Pattern HELIDON_VERSION =
            Pattern.compile("(?m)^\\[helidon\\]\\s*\\nversion\\s*=\\s*\"([^\"]+)\"");

    @Test
    void the_snapshot_is_of_the_helidon_release_the_templates_pin() {
        Snapshot snapshot = Snapshot.read();
        for (String template : TEMPLATES) {
            Matcher m = HELIDON_VERSION.matcher(resource(template + ".g8/src/main/g8/jk.toml"));
            assertThat(m.find())
                    .as("%s's jk.toml opens [helidon] with its version", template)
                    .isTrue();
            assertThat(m.group(1))
                    .as(
                            "%s pins Helidon %s but archetype-inputs.txt is of %s: re-derive the snapshot at"
                                    + " the template's release (HELIDON_ARCHETYPE_CAPTURE, see"
                                    + " HelidonArchetypeUpstreamTest) and map what changed here",
                            template, m.group(1), snapshot.tag())
                    .isEqualTo(snapshot.tag());
        }
    }

    @Test
    void every_archetype_input_is_a_template_question_or_says_why_not() {
        Map<String, Input> inputs = Snapshot.read().inputs();
        // Fake-green floor: an empty snapshot maps trivially.
        assertThat(inputs).as("archetype inputs in the snapshot").hasSizeGreaterThanOrEqualTo(20);
        assertThat(INPUTS.keySet())
                .as("archetype inputs with no entry in INPUTS are new upstream: give each a template question"
                        + " or the reason jk does not ask it; entries with no input are stale")
                .containsExactlyInAnyOrderElementsOf(inputs.keySet());

        Set<String> options = new TreeSet<>();
        for (Input in : inputs.values()) {
            if (!BY_OPTION.equals(INPUTS.get(in.path()))) continue;
            assertThat(in.options())
                    .as("%s is answered by option, so it has options", in.path())
                    .isNotEmpty();
            for (String o : in.options()) options.add(in.path() + "=" + o);
        }
        assertThat(OPTIONS.keySet())
                .as("options of a by-option input with no entry in OPTIONS are new upstream: give each a"
                        + " template answer or the reason jk does not offer it; entries with no option are stale")
                .containsExactlyInAnyOrderElementsOf(options);
    }

    @Test
    void every_mapped_question_is_asked_by_each_template_with_the_mapped_answer() {
        for (String template : TEMPLATES) {
            Map<String, List<String>> asks = asks(template);
            for (String target : targets()) {
                if (target.startsWith("template:")) {
                    String id = target.substring("template:".length());
                    assertThat(TEMPLATES)
                            .as("%s names a template this plugin bundles", target)
                            .contains(id);
                    continue;
                }
                String ask = target.substring("ask:".length());
                String key = ask.contains("=") ? ask.substring(0, ask.indexOf('=')) : ask;
                assertThat(asks).as("%s asks %s", template, key).containsKey(key);
                if (ask.contains("=")) {
                    assertThat(asks.get(key))
                            .as("%s offers %s", template, ask)
                            .contains(ask.substring(ask.indexOf('=') + 1));
                }
            }
        }
    }

    @Test
    void every_template_question_answers_an_archetype_input_or_is_jks_own() {
        Set<String> mapped = new TreeSet<>();
        for (String target : targets()) {
            if (target.startsWith("ask:")) mapped.add(target.substring("ask:".length()));
        }
        for (String template : TEMPLATES) {
            for (var ask : asks(template).entrySet()) {
                String key = ask.getKey();
                if (JK_ONLY.containsKey(key)) continue;
                assertThat(mapped.contains(key) || mapped.stream().anyMatch(m -> m.startsWith(key + "=")))
                        .as("%s asks %s, which neither answers an archetype input nor is in JK_ONLY", template, key)
                        .isTrue();
                for (String option : ask.getValue()) {
                    String answer = key + "=" + option;
                    assertThat(JK_ONLY.containsKey(answer) || mapped.contains(answer))
                            .as(
                                    "%s offers %s, which neither answers an archetype option nor is in JK_ONLY",
                                    template, answer)
                            .isTrue();
                }
            }
        }
    }

    /** Every {@code ask:} and {@code template:} target in {@link #INPUTS} and {@link #OPTIONS}. */
    private static Set<String> targets() {
        Set<String> out = new TreeSet<>();
        for (String v : INPUTS.values()) if (v.startsWith("ask:")) out.add(v);
        for (String v : OPTIONS.values()) if (v.startsWith("ask:") || v.startsWith("template:")) out.add(v);
        return out;
    }

    /**
     * {@code template}'s questions from its {@code .jk-template.toml}: each {@code [[ask]]}'s key,
     * with a choice's or multi's option ids (empty for a bool or text).
     */
    static Map<String, List<String>> asks(String template) {
        String toml = resource(template + ".g8/.jk-template.toml");
        Map<String, List<String>> out = new LinkedHashMap<>();
        String[] blocks = toml.split("(?m)^\\[\\[ask\\]\\]\\s*$");
        for (int i = 1; i < blocks.length; i++) {
            String block = blocks[i];
            Matcher key = Pattern.compile("(?m)^key\\s*=\\s*\"([^\"]+)\"").matcher(block);
            assertThat(key.find()).as("an [[ask]] of %s has a key", template).isTrue();
            List<String> options = new ArrayList<>();
            Matcher opts = Pattern.compile("(?ms)^options\\s*=\\s*\\[(.*?)^\\]").matcher(block);
            if (opts.find()) {
                Matcher id = Pattern.compile("id\\s*=\\s*\"([^\"]+)\"").matcher(opts.group(1));
                while (id.find()) options.add(id.group(1));
            }
            out.put(key.group(1), List.copyOf(options));
        }
        assertThat(out).as("%s's questions", template).isNotEmpty();
        return out;
    }

    /** A file of a template the plugin bundles, from the test classpath. */
    static String resource(String path) {
        String name = "/templates/java/" + path;
        try (InputStream in = HelidonArchetypeOptionsTest.class.getResourceAsStream(name)) {
            if (in == null) throw new IllegalStateException(name + " is not on the test classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
