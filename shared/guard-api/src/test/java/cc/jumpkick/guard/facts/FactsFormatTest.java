// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.facts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.host.PathUtil;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FactsFormatTest {

    @TempDir
    Path tmp;

    private static FactsIndex rich() {
        MethodFacts annotated = new MethodFacts(
                "test",
                "(Ljava/lang/String;I)V",
                Fixtures.ACC_PUBLIC,
                List.of(Fixtures.test(), Fixtures.tag("slow")),
                List.of(List.of(), List.of(Fixtures.nullMarked())),
                List.of(CallSite.first("x/Y", "g", "(I)V", 3, "k", List.of("k", "v"))),
                List.of(new FieldRef("x/Y", "F", "J", 4, true, 2)),
                3,
                2);
        ClassFacts nulls = new ClassFacts(
                "a/b/Nulls",
                0x0200,
                null,
                List.of("java/io/Closeable", "java/lang/Runnable"),
                null,
                List.of(),
                List.of(),
                List.of(annotated),
                Set.of("java/io/Closeable"));
        return new FactsIndex(
                Map.of("a/b/Hashing", Fixtures.hashing(), "a/c/Util", Fixtures.util(), "a/b/Nulls", nulls),
                Map.of("a/b/Hashing.class", "1200:1", "a/c/Util.class", "300:2"),
                "whatever-the-builder-said");
    }

    @Test
    void bytes_read_back_to_an_equal_index_with_a_recomputed_digest() throws IOException {
        FactsIndex in = rich();
        byte[] bytes = FactsFormat.toBytes(in);
        FactsIndex out = FactsFormat.read(new ByteArrayInputStream(bytes));
        assertThat(out.classes()).isEqualTo(in.classes());
        assertThat(out.stamps()).isEqualTo(in.stamps());
        assertThat(out.bodyDigest())
                .as("the header carries the content digest, not the seed")
                .hasSize(64);
        assertThat(out.bodyDigest()).isEqualTo(FactsFormat.digestOf(in));
    }

    @Test
    void nullable_strings_survive_the_string_table() throws IOException {
        FactsIndex out = FactsFormat.read(new ByteArrayInputStream(FactsFormat.toBytes(rich())));
        ClassFacts nulls = out.classNamed("a/b/Nulls").orElseThrow();
        assertThat(nulls.superName()).isNull();
        assertThat(nulls.sourceFile()).isNull();
        assertThat(nulls.interfaces()).containsExactly("java/io/Closeable", "java/lang/Runnable");
        MethodFacts m = nulls.methods().get(0);
        assertThat(m.parameterAnnotations()).hasSize(2);
        assertThat(m.parameterAnnotations().get(1).get(0).typeName()).isEqualTo("org.jspecify.annotations.NullMarked");
        assertThat(m.calls().get(0).literals()).containsExactly("k", "v");
        assertThat(m.fieldRefs().get(0).write()).isTrue();
        assertThat(m.branches()).isEqualTo(3);
        assertThat(m.firstLine()).isEqualTo(2);
        FieldFacts calls = out.classNamed("a/b/Hashing").orElseThrow().fields().get(1);
        assertThat(calls.constantValue()).isNull();
    }

    @Test
    void serialisation_is_deterministic_and_the_digest_follows_the_content() throws IOException {
        byte[] a = FactsFormat.toBytes(rich());
        byte[] b = FactsFormat.toBytes(rich());
        assertThat(Arrays.equals(a, b)).isTrue();
        FactsIndex changed = new FactsIndex(
                Map.of("a/b/Hashing", Fixtures.hashing(), "a/c/Util", Fixtures.util()), rich().stamps(), "x");
        assertThat(FactsFormat.digestOf(changed)).isNotEqualTo(FactsFormat.digestOf(rich()));
        FactsIndex restamped = rich().withStamps(Map.of("other.class", "1:1"), "seed2");
        assertThat(FactsFormat.digestOf(restamped))
                .as("stamps are not content")
                .isEqualTo(FactsFormat.digestOf(rich()));
    }

    @Test
    void the_header_reads_without_the_body_and_the_file_is_replaced_atomically() throws IOException {
        Path file = tmp.resolve("facts/main.idx");
        FactsFormat.write(file, rich());
        assertThat(Files.isRegularFile(file)).isTrue();
        List<String> left = new ArrayList<>();
        PathUtil.forEachChild(Objects.requireNonNull(file.getParent()), (p, attrs) -> {
            left.add(p.getFileName().toString());
            return true;
        });
        assertThat(left).as("the staging file is moved, not left").containsExactly("main.idx");
        FactsFormat.Header h = FactsFormat.readHeader(file).orElseThrow();
        assertThat(h.version()).isEqualTo(FactsFormat.VERSION);
        assertThat(h.bodyDigest()).isEqualTo(FactsFormat.digestOf(rich()));
        assertThat(h.stamps())
                .containsExactly(Map.entry("a/b/Hashing.class", "1200:1"), Map.entry("a/c/Util.class", "300:2"));
        assertThat(FactsFormat.read(file).classes()).isEqualTo(rich().classes());
    }

    @Test
    void a_missing_or_foreign_file_has_no_header() throws IOException {
        assertThat(FactsFormat.readHeader(tmp.resolve("absent.idx"))).isEmpty();
        Path text = tmp.resolve("text.idx");
        Files.writeString(text, "this is not a facts index, it is prose long enough to hold a magic int");
        assertThat(FactsFormat.readHeader(text)).isEmpty();
        Path empty = tmp.resolve("empty.idx");
        Files.write(empty, new byte[0]);
        assertThat(FactsFormat.readHeader(empty)).isEmpty();
    }

    @Test
    void a_body_read_of_something_else_fails_as_not_an_index() {
        byte[] prose = "not a facts index at all, but long enough".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> FactsFormat.read(new ByteArrayInputStream(prose)))
                .isInstanceOf(IOException.class)
                .hasMessage("not a facts index");
    }

    @Test
    void a_different_format_version_is_not_this_format() throws IOException {
        byte[] bytes = FactsFormat.toBytes(rich());
        byte[] bumped = bytes.clone();
        bumped[7] = (byte) (FactsFormat.VERSION + 1); // the version int follows the magic int
        assertThatThrownBy(() -> FactsFormat.read(new ByteArrayInputStream(bumped)))
                .isInstanceOf(IOException.class)
                .hasMessage("not a facts index");
        Path file = tmp.resolve("bumped.idx");
        Files.write(file, bumped);
        assertThat(FactsFormat.readHeader(file)).isEmpty();
    }

    @Test
    void a_truncated_body_is_an_io_error_not_a_partial_index() throws IOException {
        byte[] bytes = FactsFormat.toBytes(rich());
        byte[] cut = Arrays.copyOf(bytes, bytes.length - 40);
        assertThatThrownBy(() -> FactsFormat.read(new ByteArrayInputStream(cut)))
                .isInstanceOf(IOException.class);
    }

    @Test
    void the_empty_index_round_trips() throws IOException {
        FactsIndex out = FactsFormat.read(new ByteArrayInputStream(FactsFormat.toBytes(FactsIndex.EMPTY)));
        assertThat(out.classes()).isEmpty();
        assertThat(out.stamps()).isEmpty();
        assertThat(out.bodyDigest()).isEqualTo(FactsFormat.digestOf(FactsIndex.EMPTY));
    }

    @Test
    void the_string_table_is_shared_so_a_repeated_target_costs_one_entry() throws IOException {
        // Fifty methods calling one target against fifty methods each calling a distinct owner: the
        // second index needs forty-nine more table entries, and its bytes say so.
        FactsIndex shared = Fixtures.index(Fixtures.cls("a/Many", fiftyCalls(i -> "java/security/MessageDigest")));
        FactsIndex distinct = Fixtures.index(Fixtures.cls("a/Many", fiftyCalls(i -> "java/security/Digest" + i)));
        int sharedBytes = FactsFormat.toBytes(shared).length;
        int distinctBytes = FactsFormat.toBytes(distinct).length;
        assertThat(distinctBytes - sharedBytes)
                .as("forty-nine extra owner strings of at least twenty bytes each")
                .isGreaterThan(49 * 20);
        assertThat(FactsFormat.read(new ByteArrayInputStream(FactsFormat.toBytes(shared)))
                        .calls())
                .hasSize(50);
    }

    private static List<MethodFacts> fiftyCalls(IntFunction<String> owner) {
        List<MethodFacts> methods = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            methods.add(Fixtures.method(
                    "m" + i,
                    "()V",
                    List.of(new CallSite(
                            owner.apply(i),
                            "getInstance",
                            "(Ljava/lang/String;)Ljava/security/MessageDigest;",
                            i,
                            "SHA-256",
                            1)),
                    List.of()));
        }
        return methods;
    }
}
