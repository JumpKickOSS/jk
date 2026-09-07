// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.facts.AnnotationFacts;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.FieldFacts;
import cc.jumpkick.guard.facts.FieldRef;
import cc.jumpkick.guard.facts.MethodFacts;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The site kinds not exercised by GuardApiTest: class sites, field accesses, annotated elements, origins without a source file. */
class SitesTest {

    private static ClassFacts cls(String internal, @Nullable String source) {
        return new ClassFacts(
                internal, 1, "java/lang/Object", List.of(), source, List.of(), List.of(), List.of(), Set.of());
    }

    @Test
    void an_origin_without_a_source_file_attribute_names_the_outermost_class_file() {
        Origin nested = new Origin(cls("cc/jumpkick/host/Outer$Inner$1", null), null);
        assertThat(nested.sourceFile()).isEqualTo("cc/jumpkick/host/Outer.java");
        Origin plain = new Origin(cls("Main", null), null);
        assertThat(plain.sourceFile())
                .as("default package: no directory prefix")
                .isEqualTo("Main.java");
        assertThat(new Origin(cls("Main", "Main.kt"), null).sourceFile()).isEqualTo("Main.kt");
    }

    @Test
    void an_origin_without_a_member_keys_on_the_class_alone() {
        Origin o = new Origin(cls("a/b/C$2", "C.java"), null);
        assertThat(o.key()).isEqualTo("a.b.C$");
        assertThat(o.inPackage("a.b")).isTrue();
        assertThat(o.inPackage("a.b.c")).isFalse();
        assertThat(o.inPackage("a")).isTrue();
    }

    @Test
    void a_class_site_is_the_binary_name_at_its_source_file_with_no_line() {
        ClassSite site = new ClassSite(cls("a/b/C", "C.java"));
        assertThat(site.fingerprint()).isEqualTo("a.b.C");
        assertThat(site.file()).isEqualTo("a/b/C.java");
        assertThat(site.line()).isZero();
    }

    @Test
    void a_field_write_is_a_different_fingerprint_from_a_read() {
        Origin o = new Origin(
                cls("a/B", "B.java"), new MethodFacts("f", "()V", 1, List.of(), List.of(), List.of(), List.of(), 0, 0));
        FieldAccess read = new FieldAccess(o, new FieldRef("x/Y", "F", "I", 4, false, 1));
        FieldAccess write = new FieldAccess(o, new FieldRef("x/Y", "F", "I", 5, true, 1));
        assertThat(read.fingerprint()).isEqualTo("a.B#f()V -> x.Y#F");
        assertThat(write.fingerprint()).isEqualTo("a.B#f()V -> x.Y#F =");
        assertThat(write.line()).isEqualTo(5);
        assertThat(write.file()).isEqualTo("a/B.java");
    }

    @Test
    void an_annotated_element_answers_by_binary_type_name_and_sites_its_class() {
        AnnotationFacts tag =
                new AnnotationFacts("Lorg/junit/jupiter/api/Tag;", true, Map.of("value", List.of("slow")));
        ClassFacts c = cls("a/T", "T.java");
        Annotated method = new Annotated(
                c,
                new MethodFacts("t", "()V", 1, List.of(tag), List.of(), List.of(), List.of(), 0, 0),
                null,
                -1,
                List.of(tag));
        assertThat(method.has("org.junit.jupiter.api.Tag")).isTrue();
        assertThat(method.has("org.junit.jupiter.api.Test")).isFalse();
        assertThat(method.site().fingerprint()).isEqualTo("a.T");
        assertThat(method.site().file()).isEqualTo("a/T.java");
        Annotated field = new Annotated(c, null, new FieldFacts("x", "I", 1, null, List.of()), -1, List.of());
        assertThat(field.has("anything")).isFalse();
        assertThat(field.annotations()).isEmpty();
    }

    @Test
    void a_tool_site_is_whatever_the_tool_said() {
        ToolSite site = new ToolSite("Rule 'r' was violated (Foo.java)", "a/Foo.java", 12);
        assertThat(site.fingerprint()).isEqualTo("Rule 'r' was violated (Foo.java)");
        assertThat(site.file()).isEqualTo("a/Foo.java");
        assertThat(site.line()).isEqualTo(12);
        assertThat(new ToolSite("x", null, 0).file()).isNull();
    }

    @Test
    void a_metric_site_has_no_line_and_may_have_no_file() {
        MetricSite m = new MetricSite("server/engine#Foo/2", 130.0, null);
        assertThat(m.fingerprint()).isEqualTo("server/engine#Foo/2");
        assertThat(m.value()).isEqualTo(130.0);
        assertThat(m.file()).isNull();
        assertThat(m.line()).isZero();
    }

    @Test
    void a_text_site_fingerprints_on_the_stripped_match_but_keeps_its_line() {
        TextSite t = new TextSite("docs/a.md", 9, "\t used to be \n");
        assertThat(t.fingerprint()).isEqualTo("docs/a.md | used to be");
        assertThat(t.line()).isEqualTo(9);
        assertThat(t.file()).isEqualTo("docs/a.md");
        assertThat(t.matched()).isEqualTo("\t used to be \n");
    }

    @Test
    void a_tagged_class_copies_its_tags() {
        HashSet<String> tags = new HashSet<>(List.of("slow"));
        TaggedClass tc = new TaggedClass(cls("a/T", "T.java"), tags);
        tags.add("network");
        assertThat(tc.tags()).containsExactly("slow");
        assertThat(tc.line()).isZero();
    }
}
