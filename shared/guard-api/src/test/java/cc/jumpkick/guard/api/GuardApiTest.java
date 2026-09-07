// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.MethodFacts;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GuardApiTest {

    private static ClassFacts cls(String internal, String source) {
        return new ClassFacts(
                internal, 1, "java/lang/Object", List.of(), source, List.of(), List.of(), List.of(), Set.of());
    }

    private static MethodFacts method(String name, String desc) {
        return new MethodFacts(name, desc, 1, List.of(), List.of(), List.of(), List.of(), 0, 0);
    }

    @Test
    void the_prd_examples_are_readable_guards() throws Exception {
        GuardSuite suite = HouseRules.class.getAnnotation(GuardSuite.class);
        assertThat(suite).isNotNull();
        assertThat(suite.scope()).isEqualTo(Scope.WORKSPACE);
        Method m = HouseRules.class.getDeclaredMethod("oneJsonCodec", Facts.class, Text.class, Violations.class);
        Guard g = m.getAnnotation(Guard.class);
        assertThat(g).isNotNull();
        assertThat(g.id()).isEqualTo("one-json-codec");
        assertThat(g.instead()).isEqualTo("Jsonl.quote / Jsonl.parse");
        Method t = HouseRules.class.getDeclaredMethod("tierPartition", Model.class, Facts.class, Violations.class);
        assertThat(t.getAnnotation(Guard.class).instead()).isEmpty();
    }

    @Test
    void signatures_match_owner_globs_names_and_parameter_lists() {
        Sig any = Sig.of("java.lang.String#replace(**)");
        assertThat(any.matches("java/lang/String", "replace", "(CC)Ljava/lang/String;"))
                .isTrue();
        assertThat(any.matches(
                        "java/lang/String",
                        "replace",
                        "(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;"))
                .isTrue();
        assertThat(any.matches("java/lang/String", "replaceAll", "(CC)Ljava/lang/String;"))
                .isFalse();
        assertThat(any.matches("java/lang/StringBuilder", "replace", "(CC)Ljava/lang/String;"))
                .isFalse();
        Sig exact = Sig.of("java.lang.String#replace(java.lang.CharSequence, java.lang.CharSequence)");
        assertThat(exact.matches(
                        "java/lang/String",
                        "replace",
                        "(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;"))
                .isTrue();
        assertThat(exact.matches("java/lang/String", "replace", "(CC)Ljava/lang/String;"))
                .isFalse();
        assertThat(Sig.of("java.util.*#of").matches("java/util/List", "of", "()Ljava/util/List;"))
                .isTrue();
        assertThat(Sig.of("java.util.*#of").matches("java/util/concurrent/Flow", "of", "()V"))
                .isFalse();
        assertThat(Sig.of("java.**#*()").matches("java/util/concurrent/Flow", "of", "()V"))
                .isTrue();
        assertThat(Sig.of("java.**#*()").matches("java/util/concurrent/Flow", "of", "(I)V"))
                .isFalse();
        assertThat(Sig.parameterTypes("([BLjava/lang/String;I[[J)V"))
                .containsExactly("byte[]", "java.lang.String", "int", "long[][]");
        assertThatThrownBy(() -> Sig.of("noHash")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sites_fingerprint_without_lines_and_fold_anonymous_ordinals() {
        ClassFacts anon = cls("cc/jumpkick/jsonl/Jsonl$3", "Jsonl.java");
        Origin o = new Origin(anon, method("lambda$run$7", "()V"));
        assertThat(o.key()).isEqualTo("cc.jumpkick.jsonl.Jsonl$#lambda$run$()V");
        assertThat(o.sourceFile()).isEqualTo("cc/jumpkick/jsonl/Jsonl.java");
        assertThat(o.inPackage("cc.jumpkick")).isTrue();
        assertThat(o.inPackage("cc.jump")).isFalse();
        CallSite s = new CallSite(
                o,
                FactsSites.call("java/lang/String", "replace", "(CC)Ljava/lang/String;", 42, "\"", List.of("\"", "x")));
        assertThat(s.fingerprint())
                .isEqualTo("cc.jumpkick.jsonl.Jsonl$#lambda$run$()V -> java.lang.String#replace(CC)Ljava/lang/String;");
        assertThat(s.line()).isEqualTo(42);
        assertThat(s.nearbyLiteral("x")).isTrue();
        assertThat(s.nearbyLiteral("y")).isFalse();
        assertThat(new TextSite("a/B.java", 7, "  TODO later ").fingerprint()).isEqualTo("a/B.java | TODO later");
        assertThat(new MetricSite("a/B.java", 12, "a/B.java").fingerprint()).isEqualTo("a/B.java");
        assertThat(new TaggedClass(anon, Set.of("slow")).fingerprint()).isEqualTo("cc.jumpkick.jsonl.Jsonl$3");
        Tiers tiers = new Tiers() {
            @Override
            public Set<String> tagVocabulary() {
                return Set.of();
            }

            @Override
            public List<String> running(Set<String> tags) {
                return List.of();
            }
        };
        assertThat(tiers.fingerprint()).isEqualTo("tiers");
        assertThat(new Toolchain(Map.of("", 25), null, List.of("central")).fingerprint())
                .isEqualTo("toolchain");
    }

    @Test
    void the_owner_probe_fails_loudly_when_the_owner_is_out_of_scope() {
        Facts facts = new FactsStub(List.of(cls("cc/jumpkick/host/Hashing", "Hashing.java")));
        assertThat(Owner.require(facts, "cc.jumpkick.host.Hashing").binaryName())
                .isEqualTo("cc.jumpkick.host.Hashing");
        assertThatThrownBy(() -> Owner.require(facts, "cc.jumpkick.host.Missing"))
                .isInstanceOf(OwnerMissing.class)
                .hasMessageContaining("cc.jumpkick.host.Missing");
    }

    /** The minimum a runtime must implement; every other method is unreachable from this test. */
    private record FactsStub(List<ClassFacts> classes) implements Facts {
        @Override
        public List<CallSite> calls(Sig sig) {
            return List.of();
        }

        @Override
        public List<FieldAccess> fieldRefs() {
            return List.of();
        }

        @Override
        public List<Annotated> annotations(On on) {
            return List.of();
        }

        @Override
        public Map<String, String> constants(String ownerBinaryName) {
            return Map.of();
        }

        @Override
        public Map<String, Set<String>> packageEdges() {
            return Map.of();
        }

        @Override
        public List<TaggedClass> testClasses() {
            return List.of();
        }

        @Override
        public List<Path> classDirs() {
            return List.of();
        }
    }
}
