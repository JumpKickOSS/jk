// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import cc.jumpkick.model.Scope;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * The lockfile writer and reader are inverses on every lock the writer can produce, and the reader
 * fails only with its one typed error on any text at all.
 */
class LockfilePropertyTest {

    private static Arbitrary<String> hex(int length) {
        return Arbitraries.strings()
                .withCharRange('a', 'f')
                .withCharRange('0', '9')
                .ofLength(length);
    }

    @Provide
    Arbitrary<Lockfile.Artifact> artifacts() {
        Arbitrary<String> ga = Arbitraries.integers().between(0, 30).map(i -> "g" + (i % 7) + ":a" + i);
        Arbitrary<String> version = Arbitraries.of("1.0", "1.0.1", "2.3.4", "3.0-rc1", "4.0.0.Final", "20240101");
        Arbitrary<String> source = Arbitraries.of("central", "jk-local", "local+file:///srv/repo", "google");
        Arbitrary<String> sha = hex(64).map(h -> "sha256:" + h);
        Arbitrary<Set<Scope>> scopes =
                Arbitraries.of(Scope.class).set().ofMinSize(1).ofMaxSize(3);
        Arbitrary<List<String>> deps = ga.list().ofMaxSize(3);
        Arbitrary<String> pinnedBy =
                Arbitraries.of("platform:org.acme:bom", "lock", "manifest").injectNull(0.5);
        Arbitrary<String> path =
                Arbitraries.of("libs/a.jar", "../sibling/target/x.jar").injectNull(0.7);
        return ga.flatMap(n -> version.flatMap(v -> source.flatMap(s -> sha.injectNull(0.2)
                .flatMap(c -> scopes.flatMap(sc ->
                        deps.flatMap(d -> pinnedBy.flatMap(p -> path.flatMap(pa -> sha.injectNull(0.7)
                                .map(src -> new Lockfile.Artifact(
                                        n, v, s, c, pa, new ArrayList<>(sc), d, p, null, src))))))))));
    }

    @Provide
    Arbitrary<Lockfile> lockfiles() {
        Arbitrary<List<Lockfile.Artifact>> arts =
                artifacts().list().ofMaxSize(6).map(LockfilePropertyTest::dedupeByName);
        Arbitrary<Lockfile.JdkPin> jdk = Arbitraries.of(
                new Lockfile.JdkPin("temurin", "25.0.4", "", ""),
                new Lockfile.JdkPin("", "", "corretto", "21.0.5"),
                new Lockfile.JdkPin("temurin", "25", "temurin", ""));
        Arbitrary<String> kotlin = Arbitraries.of("2.4.10", "2.3.0").injectNull(0.6);
        Arbitrary<String> jkMin = Arbitraries.of("0.12.0", "0.13.0").injectNull(0.5);
        Arbitrary<String> digest = hex(64).injectNull(0.5);
        Arbitrary<String> projectId = hex(32).injectNull(0.5);
        return arts.flatMap(a -> jdk.injectNull(0.3)
                .flatMap(
                        j -> kotlin.flatMap(k -> jkMin.flatMap(m -> digest.flatMap(d -> projectId.map(p -> new Lockfile(
                                Lockfile.CURRENT_VERSION,
                                "jk 0.13.0",
                                Lockfile.RESOLUTION_ALGORITHM,
                                j,
                                null,
                                k,
                                null,
                                a,
                                List.of(),
                                List.of(),
                                List.of(),
                                m,
                                d,
                                p,
                                null)))))));
    }

    private static List<Lockfile.Artifact> dedupeByName(List<Lockfile.Artifact> in) {
        List<Lockfile.Artifact> out = new ArrayList<>();
        for (Lockfile.Artifact a : in) {
            if (out.stream().noneMatch(o -> o.name().equals(a.name()))) out.add(a);
        }
        return out;
    }

    @Property(tries = 300)
    void render_then_parse_then_render_is_the_identity(@ForAll("lockfiles") Lockfile lock) {
        String once = LockfileWriter.render(lock);
        Lockfile parsed = LockfileReader.parse(once);
        assertThat(LockfileWriter.render(parsed)).isEqualTo(once);
    }

    @Property(tries = 300)
    void parse_reads_back_every_field_the_writer_emits(@ForAll("lockfiles") Lockfile lock) {
        Lockfile parsed = LockfileReader.parse(LockfileWriter.render(lock));
        assertThat(parsed.version()).isEqualTo(Lockfile.CURRENT_VERSION);
        assertThat(parsed.artifacts()).hasSameSizeAs(lock.artifacts());
        for (Lockfile.Artifact want : lock.artifacts()) {
            // The writer orders rows by name; identity is the name, not the position.
            Lockfile.Artifact got = parsed.artifacts().stream()
                    .filter(a -> a.name().equals(want.name()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("row lost: " + want.name()));
            assertThat(got.version()).isEqualTo(want.version());
            assertThat(got.source()).isEqualTo(want.source());
            assertThat(got.checksum()).isEqualTo(want.checksum());
            assertThat(got.scopes()).containsExactlyInAnyOrderElementsOf(want.scopes());
            assertThat(got.deps()).containsExactlyInAnyOrderElementsOf(want.deps()); // the writer sorts deps
        }
        assertThat(parsed.jkMin()).isEqualTo(lock.jkMin() == null ? LockfileWriter.FORMAT_FLOOR : lock.jkMin());
        assertThat(parsed.kotlin()).isEqualTo(lock.kotlin());
        assertThat(parsed.projectId()).isEqualTo(lock.projectId());
    }

    @Property(tries = 500)
    void any_text_parses_or_fails_with_the_one_typed_error(@ForAll("tomlish") String text) {
        assertThatCode(() -> {
                    try {
                        LockfileReader.parse(text);
                    } catch (IllegalArgumentException expected) {
                        // the reader's one error: a parse problem, a missing key, a bad version
                    }
                })
                .as("input: %s", text)
                .doesNotThrowAnyException();
    }

    @Provide
    Arbitrary<String> tomlish() {
        Arbitrary<String> token = Arbitraries.of(
                "version = 1\n",
                "version = 2\n",
                "version = \"1\"\n",
                "version =\n",
                "generated-by = \"jk\"\n",
                "resolution-algorithm = \"pubgrub-v1\"\n",
                "[jdk]\n",
                "[[artifact]]\n",
                "name = \"g:a\"\n",
                "version = \"1.0\"\n",
                "source = \"central\"\n",
                "checksum = 7\n",
                "scopes = [\"main\"]\n",
                "scopes = [\"nope\"]\n",
                "deps = [1, 2]\n",
                "[native]\n",
                "jk-min = 3\n",
                "[[module]]\n",
                "path = \".\"\n",
                "= \n",
                "[\n",
                "\"\"\"\n",
                "#\n",
                " \n");
        return Arbitraries.oneOf(
                token.list().ofMaxSize(12).map(l -> String.join("", l)),
                Arbitraries.strings().ofMaxLength(80));
    }
}
