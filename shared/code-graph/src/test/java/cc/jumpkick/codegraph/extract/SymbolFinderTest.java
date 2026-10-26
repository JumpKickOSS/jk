// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.codegraph.CodeGraph;
import cc.jumpkick.codegraph.Fragment;
import cc.jumpkick.codegraph.Linker;
import cc.jumpkick.codegraph.Modules;
import cc.jumpkick.codegraph.Scope;
import cc.jumpkick.codegraph.SymbolFinder;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class SymbolFinderTest {

    private static String find(CodeGraph g, Map<String, String> filters, @Nullable String name) {
        return SymbolFinder.of(filters, name).find(g, Scope.ALL, 2000);
    }

    private static List<String> names(String answer) {
        return answer.lines().skip(1).map(l -> l.split("  ")[1]).toList();
    }

    @Test
    void filtersByKindVisibilityAnnotationAndName() throws IOException {
        CodeGraph shop = ExtractorsTest.shop();
        assertThat(names(find(shop, Map.of("kind", "interface"), null))).containsExactly("com.acme.shop.Auditable");
        assertThat(names(find(shop, Map.of("kind", "record"), null))).containsExactly("com.acme.shop.Item");
        assertThat(names(find(shop, Map.of("annotated", "@Override"), null))).containsExactly("OrderService.audit");
        assertThat(names(find(shop, Map.of(), "*service")))
                .containsExactly("com.acme.shop.BaseService", "com.acme.shop.OrderService");
        assertThat(names(find(shop, Map.of("kind", "member", "visibility", "protected"), null)))
                .containsExactly("BaseService.log");
        assertThat(find(shop, Map.of("kind", "type"), "Order*"))
                .startsWith(
                        "1 match\nclass public  com.acme.shop.OrderService  src/main/java/com/acme/shop/OrderService.java:6-23");
    }

    @Test
    void supertypesMatchUpTheHierarchyByAnyName() throws IOException {
        List<Fragment> fragments = new ArrayList<>();
        Map<String, String> files = Map.of(
                "src/main/java/a/Port.java", "package a;\npublic interface Port {}\n",
                "src/main/java/a/Base.java",
                        "package a;\npublic abstract class Base implements Port, java.io.Serializable {}\n",
                "src/main/java/a/Impl.java",
                        "package a;\nimport org.lib.Handler;\npublic class Impl extends Base implements Handler {}\n",
                "src/test/java/a/ImplTest.java", "package a;\nclass ImplTest extends Impl {}\n");
        files.forEach((rel, text) ->
                fragments.add(Objects.requireNonNull(Extractors.forPath(rel)).extract(rel, text)));
        CodeGraph g = Linker.link(fragments);
        assertThat(names(find(g, Map.of("implements", "Port"), null)))
                .containsExactly("a.Base", "a.Impl", "a.ImplTest");
        assertThat(names(find(g, Map.of("implements", "Serializable"), null)))
                .containsExactly("a.Base", "a.Impl", "a.ImplTest");
        assertThat(names(find(g, Map.of("implements", "org.lib.Handler"), null)))
                .containsExactly("a.Impl", "a.ImplTest");
        assertThat(names(find(g, Map.of("extends", "Base"), null))).containsExactly("a.Impl", "a.ImplTest");
        assertThat(names(find(g, Map.of("extends", "Port"), null))).isEmpty();
        Scope main = Scope.of(null, List.of(), "main", Modules.NONE);
        assertThat(SymbolFinder.of(Map.of("extends", "Base"), null).find(g, main, 2000))
                .doesNotContain("ImplTest");
    }

    @Test
    void aNestedSupertypeMatchesByItsOuterQualifiedName() {
        String rel = "src/main/java/a/Preflight.java";
        String text = """
                package a;
                public final class Preflight {
                    sealed interface Main permits Unique, None {}
                    record Unique(String cls) implements Main {}
                    record None() implements Main {}
                }
                """;
        CodeGraph g = Linker.link(
                List.of(Objects.requireNonNull(Extractors.forPath(rel)).extract(rel, text)));
        assertThat(names(find(g, Map.of("implements", "Preflight.Main"), null)))
                .containsExactlyInAnyOrder("a.Preflight.None", "a.Preflight.Unique");
        assertThat(names(find(g, Map.of("implements", "Other.Main"), null))).isEmpty();
    }

    @Test
    void anEmptyFilterIsRefused() throws IOException {
        CodeGraph shop = ExtractorsTest.shop();
        assertThatThrownBy(() -> find(shop, Map.of(), null)).hasMessageContaining("needs a filter");
    }
}
