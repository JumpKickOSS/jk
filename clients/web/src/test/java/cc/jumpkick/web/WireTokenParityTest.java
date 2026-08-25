// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.web;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.protocol.EngineProtocol;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * {@code wire.js} is the SPA's one copy of the engine event vocabulary, and this test is why the
 * copy can be trusted: generation-as-guard for a deliberately no-build-step SPA. Every {@code
 * EVENT} token must be a value {@link EngineProtocol} declares; every {@code SSE} token must be
 * the dashboard's own SSE-layer vocabulary — written as a literal by the engine's SSE publishers
 * and spelled by no protocol constant. A third arm keeps the vocabulary from leaking back out:
 * no other shipped module may type a hyphenated token as a string literal.
 *
 * <p>Each arm fails loudly on an empty parse — a token map the regex cannot see is a guard
 * scanning nothing.
 */
class WireTokenParityTest {

    private static final Pattern TOKEN = Pattern.compile("(\\w+): '([^']+)',");

    /** The engine sources that write the SSE-layer names ({@code request-start}, {@code cache}, …). */
    private static final List<String> SSE_WRITERS = List.of(
            "server/engine/src/main/java/cc/jumpkick/engine/SsePublisher.java",
            "server/engine/src/main/java/cc/jumpkick/engine/LiveRuns.java",
            "server/engine/src/main/java/cc/jumpkick/engine/http/LiveVitals.java");

    @Test
    void every_EVENT_token_is_an_EngineProtocol_value() throws Exception {
        Map<String, String> event = tokens("EVENT");
        assertThat(event)
                .as("EVENT tokens parsed out of wire.js — zero means the parse broke")
                .hasSizeGreaterThanOrEqualTo(12);

        Set<String> protocol = engineProtocolValues();
        assertThat(protocol).hasSizeGreaterThan(100);
        event.forEach((name, value) -> assertThat(protocol)
                .as("wire.js EVENT.%s = '%s' must be a value EngineProtocol declares", name, value)
                .contains(value));
    }

    @Test
    void every_SSE_token_is_engine_written_and_not_a_protocol_value() throws Exception {
        Map<String, String> sse = tokens("SSE");
        assertThat(sse)
                .as("SSE tokens parsed out of wire.js — zero means the parse broke")
                .hasSize(5);

        Set<String> protocol = engineProtocolValues();
        StringBuilder writers = new StringBuilder();
        Path root = repoRoot();
        for (String rel : SSE_WRITERS) {
            Path f = root.resolve(rel);
            assertThat(f)
                    .as("SSE writer source moved — update SSE_WRITERS and the :web:test inputs")
                    .exists();
            writers.append(Files.readString(f)).append('\n');
        }
        String engineSide = writers.toString();
        sse.forEach((name, value) -> {
            assertThat(protocol)
                    .as("wire.js SSE.%s = '%s' is now a protocol constant — move it to EVENT", name, value)
                    .doesNotContain(value);
            assertThat(engineSide)
                    .as("wire.js SSE.%s = '%s' is written by none of the engine's SSE publishers", name, value)
                    .contains("\"" + value + "\"");
        });
    }

    /** The re-introduction ratchet: a hyphenated token typed anywhere else is a second copy. */
    @Test
    void no_other_module_types_a_hyphenated_token() throws Exception {
        Map<String, String> all = new LinkedHashMap<>(tokens("EVENT"));
        all.putAll(tokens("SSE"));
        List<String> hyphenated =
                all.values().stream().filter(v -> v.contains("-")).toList();
        assertThat(hyphenated).hasSizeGreaterThanOrEqualTo(9);

        Path assets = WebClientJsTest.moduleRoot().resolve("src/main/resources/web");
        List<String> hits = modules(assets).stream()
                .filter(m -> !m.getFileName().toString().equals("wire.js"))
                .flatMap(m -> {
                    String body = read(m);
                    return hyphenated.stream()
                            .filter(v -> body.contains("'" + v + "'") || body.contains("\"" + v + "\""))
                            .map(v -> m.getFileName() + ": '" + v + "'");
                })
                .toList();
        assertThat(hits)
                .as("wire event names typed outside wire.js — import EVENT/SSE from './wire.js' instead")
                .isEmpty();
    }

    // ---- plumbing ------------------------------------------------------------------------------

    /** The {@code name: 'value',} pairs inside {@code export const <mapName> = Object.freeze({...})}. */
    private static Map<String, String> tokens(String mapName) throws IOException {
        String wire = Files.readString(WebClientJsTest.moduleRoot().resolve("src/main/resources/web/wire.js"));
        int start = wire.indexOf("export const " + mapName + " = Object.freeze({");
        assertThat(start).as("wire.js no longer declares %s", mapName).isNotNegative();
        int end = wire.indexOf("});", start);
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = TOKEN.matcher(wire.substring(start, end));
        while (m.find()) {
            out.put(m.group(1), m.group(2));
        }
        return out;
    }

    /** Every distinct non-empty String constant value {@link EngineProtocol} declares. */
    private static Set<String> engineProtocolValues() throws Exception {
        Set<String> values = new LinkedHashSet<>();
        for (Field f : EngineProtocol.class.getDeclaredFields()) {
            if (f.getType() == String.class && Modifier.isStatic(f.getModifiers())) {
                String v = (String) f.get(null);
                if (v != null && !v.isEmpty()) values.add(v);
            }
        }
        return values;
    }

    private static List<Path> modules(Path assets) throws IOException {
        try (Stream<Path> entries = Files.list(assets)) {
            return entries.filter(p -> p.getFileName().toString().endsWith(".js"))
                    .sorted()
                    .toList();
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + p, e);
        }
    }

    /** The checkout root above {@code clients/web} — where the engine's SSE writer sources live. */
    private static Path repoRoot() throws IOException {
        for (Path d = WebClientJsTest.moduleRoot().toAbsolutePath(); d != null; d = d.getParent()) {
            if (Files.isRegularFile(d.resolve("settings.gradle.kts"))
                    && Files.isDirectory(d.resolve("server/engine"))) {
                return d;
            }
        }
        throw new IOException("cannot locate the jk checkout root above clients/web");
    }
}
