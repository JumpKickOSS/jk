// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.codegraph.CodeGraph;
import cc.jumpkick.codegraph.GraphTools;
import cc.jumpkick.codegraph.Linker;
import cc.jumpkick.codegraph.Scope;
import cc.jumpkick.codegraph.SymbolReader;
import cc.jumpkick.codegraph.SymbolReader.View;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SymbolReaderTest {

    private static CodeGraph shop;

    @BeforeAll
    static void load() throws IOException {
        shop = ExtractorsTest.shop();
    }

    private static @Nullable String file(String rel) {
        for (String name : List.of("../shop/" + rel + ".txt", "../shop/" + rel)) {
            try (InputStream in = SymbolReaderTest.class.getResourceAsStream(name)) {
                if (in != null) return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return null;
    }

    private static String read(String address, @Nullable View view, int budget) {
        return new SymbolReader(shop, Scope.ALL, SymbolReaderTest::file).read(address, view, budget);
    }

    @Test
    void aMemberReadsAsItsNumberedSourceSpan() {
        String out = read("OrderService.audit", null, 2000);
        assertThat(out)
                .startsWith("== OrderService.audit  src/main/java/com/acme/shop/OrderService.java:20-22")
                .contains("20|     public void audit(String what) {")
                .contains("22|     }")
                .doesNotContain("23| }");
    }

    @Test
    void aTypeReadsWholeAndAQualifiedNameFindsIt() {
        assertThat(read("com.acme.shop.OrderService", View.CONTENT, 2000))
                .contains(" 6| public class OrderService")
                .contains("23| }");
    }

    @Test
    void structureShowsDeclarationsWithoutBodies() {
        String out = read("OrderService", View.STRUCTURE, 2000);
        assertThat(out)
                .contains(" 6| public class OrderService extends BaseService implements Auditable")
                .contains("10|   public long total(List<Item> items)")
                .contains("@Override")
                .contains("20|   public void audit(String what)")
                .doesNotContain("calculator.sum");
    }

    @Test
    void membersAndKotlinExpressionBodies() {
        assertThat(read("PriceCalculator.*", View.CONTENT, 2000))
                .contains("4|     fun sum(items: List<Item>): Long = items.sumOf { it.cents }")
                .doesNotContain("formatCents");
        assertThat(read("formatCents", View.STRUCTURE, 2000)).contains("7| fun formatCents(cents: Long): String");
    }

    @Test
    void theBudgetPicksTheRichestViewThatFits() {
        String tight = read("src/main/java/**", null, 150);
        assertThat(tight).doesNotContain("| import").contains("view: ");
        String headlines = read("src/main/java/**", View.HEADLINE, 2000);
        assertThat(headlines)
                .contains("src/main/java/com/acme/shop/OrderService.java")
                .doesNotContain("|");
    }

    @Test
    void filesLineRangesAndExclusions() {
        assertThat(read("src/main/java/com/acme/shop/OrderService.java#line=9,10", View.CONTENT, 2000))
                .contains(" 9|     // NOTE: totals")
                .contains("10|     public long total")
                .doesNotContain("11|");
        assertThat(read("OrderService.java#line=10", View.CONTENT, 2000)).contains("10|     public long total");
        assertThat(read("src/main/java/**;!OrderService.java", View.HEADLINE, 2000))
                .doesNotContain("OrderService.java");
        assertThat(read("src/main/java/**;!**/OrderService.java", View.HEADLINE, 2000))
                .contains("BaseService.java")
                .doesNotContain("OrderService.java");
    }

    @Test
    void aTypeNameIsNotAmbiguousWithItsConstructor() {
        CodeGraph g = Linker.link(List.of(Objects.requireNonNull(Extractors.forPath("src/main/java/a/Pet.java"))
                .extract("src/main/java/a/Pet.java", "package a;\npublic class Pet {\n    public Pet() {}\n}\n")));
        String out = new SymbolReader(g, Scope.ALL, rel -> "package a;\npublic class Pet {\n    public Pet() {}\n}\n")
                .read("Pet", View.HEADLINE, 2000);
        assertThat(out).startsWith("a.Pet [class public]");
        assertThat(new SymbolReader(g, Scope.ALL, rel -> null).read("Pet.Pet", View.HEADLINE, 2000))
                .startsWith("Pet.Pet [constructor public]");
    }

    @Test
    void eachOverloadHeadlinesAtItsOwnLines() {
        String src = """
                package a;
                public class Owner {
                    /** By name. */
                    public String pet(String name) {
                        return name;
                    }

                    public String pet(Integer id) {
                        return "" + id;
                    }
                }
                """;
        CodeGraph g = Linker.link(List.of(Objects.requireNonNull(Extractors.forPath("src/main/java/a/Owner.java"))
                .extract("src/main/java/a/Owner.java", src)));
        String out = new SymbolReader(g, Scope.ALL, rel -> src).read("Owner.pet", View.HEADLINE, 2000);
        assertThat(out)
                .contains("Owner.pet [method public]  src/main/java/a/Owner.java:4-6  By name.")
                .contains("Owner.pet [method public]  src/main/java/a/Owner.java:8-10");
        assertThat(out.lines().filter(l -> l.endsWith("By name."))).hasSize(1);
    }

    @Test
    void aRecordComponentIsItsAccessor() {
        String src = """
                package a;
                public record Point(
                        @Deprecated int x,
                        java.util.Map<String, Integer> tags) {
                    int sum() { return x() + tags().size(); }
                }
                """;
        CodeGraph g = Linker.link(List.of(Objects.requireNonNull(Extractors.forPath("src/main/java/a/Point.java"))
                .extract("src/main/java/a/Point.java", src)));
        SymbolReader reader = new SymbolReader(g, Scope.ALL, rel -> src);
        assertThat(reader.read("Point.x", View.HEADLINE, 2000))
                .startsWith("Point.x [method public]  src/main/java/a/Point.java:3-3");
        assertThat(reader.read("Point.tags", View.HEADLINE, 2000))
                .startsWith("Point.tags [method public]  src/main/java/a/Point.java:4-4");
        assertThat(new GraphTools(g).neighbors("Point.tags", "", 2000)).startsWith("Neighbors of Point.tags():");
    }

    @Test
    void anOverloadedOrUnknownNameIsAnsweredPlainly() {
        assertThat(read("total", null, 2000)).startsWith("== OrderService.total");
        assertThat(read("NoSuchThing", null, 2000)).isEqualTo("No symbol 'NoSuchThing'.");
        assertThatThrownBy(() -> View.of("diff")).hasMessageContaining("content, structure or headline");
    }
}
