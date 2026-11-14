// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A test source's annotation count is read once per content, however many estimates ask, and a tag
 * filter selects from it as the runner would.
 */
class TestSupportCountTest {

    @Test
    void a_source_is_counted_again_only_when_it_changes(@TempDir Path dir) throws Exception {
        Path src =
                Files.writeString(dir.resolve("FooTest.java"), "class FooTest { @Test void a() {} @Test void b() {} }");
        assertThat(TestSupport.countTests(src)).isEqualTo(2);

        Files.writeString(src, "class FooTest { @Test void a() {} @Test void b() {} @Test void c() {} }");
        Files.setLastModifiedTime(src, FileTime.fromMillis(System.currentTimeMillis() + 5_000));
        assertThat(TestSupport.countTests(src))
                .as("a changed file is counted again")
                .isEqualTo(3);

        assertThat(TestSupport.countTests(dir.resolve("Missing.java"))).isZero();
    }

    private static final String INTEGRATION_CLASS = """
            package a;

            import org.junit.jupiter.api.Tag;
            import org.junit.jupiter.api.Test;

            @Tag("integration")
            class EngineIT {
                @Test void one() {}
                @Test void two() {}
                @Test @Tag("slow") void three() {}
            }
            """;

    private static final String UNIT_CLASS = """
            package a;

            public final class UnitTest {
                @Test void one() {}
                @Test @Tag("integration") void spawnsAnEngine() {}
                @Test void two() {}
            }
            """;

    @Test
    void a_class_tagged_with_an_included_tag_counts_whole_and_an_untagged_class_only_its_tagged_members() {
        var tagged = TestSupport.factsOf(INTEGRATION_CLASS);
        var unit = TestSupport.factsOf(UNIT_CLASS);
        List<String> integration = List.of("integration");
        assertThat(tagged.selected(integration, List.of())).isEqualTo(3);
        assertThat(unit.selected(integration, List.of()))
                .as("its one tagged member")
                .isEqualTo(1);
        assertThat(tagged.selected(integration, List.of("slow")))
                .as("less the member the run excludes")
                .isEqualTo(2);
    }

    @Test
    void the_default_tier_drops_a_class_its_exclude_list_names_and_the_members_it_names() {
        List<String> slowTiers = List.of("integration", "slow");
        assertThat(TestSupport.factsOf(INTEGRATION_CLASS).selected(List.of(), slowTiers))
                .isZero();
        assertThat(TestSupport.factsOf(UNIT_CLASS).selected(List.of(), slowTiers))
                .isEqualTo(2);
        assertThat(TestSupport.factsOf(UNIT_CLASS).selected(List.of(), List.of()))
                .isEqualTo(3);
    }

    @Test
    void tags_inside_a_tags_container_count_as_the_class_s_own() {
        var facts = TestSupport.factsOf(
                "@Tags({@Tag(\"slow\"), @Tag(\"network\")})\nclass FetchTest { @Test void a() {} @Test void b() {} }");
        assertThat(facts.classTags()).containsExactlyInAnyOrder("slow", "network");
        assertThat(facts.selected(List.of("network"), List.of())).isEqualTo(2);
    }
}
