// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.guard.extract.FactsExtractor;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.FactsIndex;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.SourceFileAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The API a dependency move takes away, read off two jars' classes, and the project's uses of it at
 * {@code file:line}.
 */
class ApiBreaksTest {

    private static final int PUBLIC = ClassFile.ACC_PUBLIC;
    private static final int PUBLIC_STATIC = ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC;
    private static final ClassDesc STRING = ConstantDescs.CD_String;
    private static final MethodTypeDesc S_TO_S = MethodTypeDesc.of(STRING, STRING);
    private static final MethodTypeDesc SS_TO_S = MethodTypeDesc.of(STRING, STRING, STRING);

    @Test
    void lists_what_was_removed_renamed_and_changed_signature() {
        FactsIndex before = index(
                lib("lib/Text", c -> {
                    method(c, "trim", S_TO_S, PUBLIC_STATIC);
                    method(c, "pad", S_TO_S, PUBLIC_STATIC);
                    method(c, "strip", SS_TO_S, PUBLIC_STATIC);
                    method(c, "kept", S_TO_S, PUBLIC_STATIC);
                    c.withField("LIMIT", ConstantDescs.CD_int, PUBLIC_STATIC);
                }),
                lib("lib/Gone", c -> {}),
                lib("lib/old/Moved", c -> {}));
        FactsIndex after = index(
                lib("lib/Text", c -> {
                    method(c, "trimmed", S_TO_S, PUBLIC_STATIC);
                    method(c, "pad", SS_TO_S, PUBLIC_STATIC);
                    method(c, "kept", S_TO_S, PUBLIC_STATIC);
                    c.withField("LIMIT", ConstantDescs.CD_long, PUBLIC_STATIC);
                }),
                lib("lib/fresh/Moved", c -> {}));

        List<ApiBreaks.Change> changes = ApiBreaks.diff(before, after);

        assertThat(changes)
                .extracting(ApiBreaks.Change::kind, ApiBreaks.Change::display, ApiBreaks.Change::now)
                .containsExactly(
                        tuple(ApiBreaks.Kind.REMOVED, "lib.Gone", List.of()),
                        tuple(
                                ApiBreaks.Kind.SIGNATURE_CHANGED,
                                "lib.Text.pad(String): String",
                                List.of("pad(String, String): String")),
                        tuple(ApiBreaks.Kind.REMOVED, "lib.Text.strip(String, String): String", List.of()),
                        tuple(
                                ApiBreaks.Kind.RENAMED,
                                "lib.Text.trim(String): String",
                                List.of("trimmed(String): String")),
                        tuple(ApiBreaks.Kind.SIGNATURE_CHANGED, "lib.Text.LIMIT", List.of("long LIMIT")),
                        tuple(ApiBreaks.Kind.RENAMED, "lib.old.Moved", List.of("lib.fresh.Moved")));
    }

    @Test
    void a_member_moved_up_to_a_superclass_in_the_jar_is_not_gone() {
        FactsIndex before = index(lib("lib/Child", c -> method(c, "go", S_TO_S, PUBLIC)));
        FactsIndex after =
                index(lib("lib/Base", c -> method(c, "go", S_TO_S, PUBLIC)), lib("lib/Child", "lib/Base", c -> {}));

        assertThat(ApiBreaks.diff(before, after)).isEmpty();
    }

    @Test
    void joins_each_change_to_the_project_calls_that_reach_it() {
        FactsIndex before = index(
                lib("lib/Text", c -> {
                    method(c, "trim", S_TO_S, PUBLIC_STATIC);
                    method(c, "kept", S_TO_S, PUBLIC_STATIC);
                }),
                lib("lib/Base", c -> method(c, "go", S_TO_S, PUBLIC)),
                lib("lib/Gone", c -> {}));
        FactsIndex after =
                index(lib("lib/Text", c -> method(c, "kept", S_TO_S, PUBLIC_STATIC)), lib("lib/Base", c -> {}));
        ClassFacts app = facts(ClassFile.of().build(ClassDesc.ofInternalName("app/App"), c -> {
            c.withFlags(PUBLIC);
            c.with(SourceFileAttribute.of("App.java"));
            c.withMethodBody("run", MethodTypeDesc.of(ConstantDescs.CD_void), PUBLIC_STATIC, b -> {
                b.lineNumber(12);
                b.ldc("x");
                b.invokestatic(ClassDesc.ofInternalName("lib/Text"), "trim", S_TO_S);
                b.pop();
                b.lineNumber(13);
                b.ldc("y");
                b.invokestatic(ClassDesc.ofInternalName("lib/Text"), "kept", S_TO_S);
                b.pop();
                b.lineNumber(14);
                b.aconst_null();
                b.ldc("z");
                b.invokevirtual(ClassDesc.ofInternalName("app/Mine"), "go", S_TO_S);
                b.pop();
                b.return_();
            });
        }));
        ClassFacts mine = facts(ClassFile.of().build(ClassDesc.ofInternalName("app/Mine"), c -> {
            c.withFlags(PUBLIC);
            c.withSuperclass(ClassDesc.ofInternalName("lib/Gone"));
            c.with(SourceFileAttribute.of("Mine.java"));
            c.withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, PUBLIC, b -> {
                b.lineNumber(5);
                b.aload(0);
                b.invokespecial(ClassDesc.ofInternalName("lib/Gone"), ConstantDescs.INIT_NAME, ConstantDescs.MTD_void);
                b.return_();
            });
        }));
        ClassFacts sub = facts(ClassFile.of().build(ClassDesc.ofInternalName("app/Sub"), c -> {
            c.withFlags(PUBLIC);
            c.withSuperclass(ClassDesc.ofInternalName("lib/Base"));
            c.with(SourceFileAttribute.of("Sub.java"));
            c.withMethodBody("call", ConstantDescs.MTD_void, PUBLIC, b -> {
                b.lineNumber(9);
                b.aload(0);
                b.ldc("q");
                b.invokevirtual(ClassDesc.ofInternalName("app/Sub"), "go", S_TO_S);
                b.pop();
                b.return_();
            });
        }));
        List<ApiBreaks.Change> changes = ApiBreaks.diff(before, after);
        ApiBreaks.Classes project =
                new ApiBreaks.Classes(index(app, mine, sub), c -> "src/main/java/app/" + c.sourceFile());

        Map<ApiBreaks.Change, List<ApiBreaks.Site>> sites = ApiBreaks.join(changes, before, List.of(project));

        Map<String, List<String>> byChange = new LinkedHashMap<>();
        sites.forEach((c, s) -> byChange.put(
                c.display(), s.stream().map(x -> x.at() + " " + x.from()).toList()));
        assertThat(byChange)
                .containsOnly(
                        Map.entry("lib.Text.trim(String): String", List.of("src/main/java/app/App.java:12 App.run")),
                        Map.entry("lib.Base.go(String): String", List.of("src/main/java/app/Sub.java:9 Sub.call")),
                        Map.entry(
                                "lib.Gone",
                                List.of(
                                        "src/main/java/app/Mine.java:5 Mine",
                                        "src/main/java/app/Mine.java:5 Mine.Mine")));

        ApiBreaks.Report report = new ApiBreaks.Report("acme:lib", "1.0", "2.0", changes, sites);
        assertThat(report.breaks()).isEqualTo(4);
        assertThat(report.text(50))
                .startsWith("acme:lib 1.0 → 2.0: 3 API changes, 3 used here at 4 places\n")
                .contains("  removed  lib.Text.trim(String): String\n    src/main/java/app/App.java:12  App.run");
        assertThat(report.text(1)).endsWith("… 2 more changes");
    }

    @Test
    void a_list_of_places_cut_by_the_row_limit_says_how_many_it_left_out() {
        ApiBreaks.Change gone = new ApiBreaks.Change(ApiBreaks.Kind.REMOVED, "lib/Gone", "", false, List.of());
        ApiBreaks.Change kept = new ApiBreaks.Change(ApiBreaks.Kind.REMOVED, "lib/Kept", "", false, List.of());
        List<ApiBreaks.Site> at = List.of(
                new ApiBreaks.Site("src/A.java", 1, "A.a"),
                new ApiBreaks.Site("src/B.java", 2, "B.b"),
                new ApiBreaks.Site("src/C.java", 3, "C.c"));
        ApiBreaks.Report report = new ApiBreaks.Report("acme:lib", "1.0", "2.0", List.of(gone, kept), Map.of(gone, at));

        assertThat(report.text(2))
                .isEqualTo(String.join(
                        "\n",
                        "acme:lib 1.0 → 2.0: 2 API changes, 1 used here at 3 places",
                        "  removed  lib.Gone",
                        "    src/A.java:1  A.a",
                        "    … 2 more places",
                        "… 1 more change"));
        assertThat(report.text(1)).contains("  removed  lib.Gone\n    … 3 more places\n");
        assertThat(report.text(5)).doesNotContain("more place").endsWith("  removed  lib.Kept");
    }

    // ---- fixture ---------------------------------------------------------------

    private static FactsIndex index(ClassFacts... classes) {
        Map<String, ClassFacts> m = new LinkedHashMap<>();
        for (ClassFacts c : classes) m.put(c.name(), c);
        return new FactsIndex(m, Map.of(), "");
    }

    private static ClassFacts lib(String name, Consumer<ClassBuilder> body) {
        return lib(name, null, body);
    }

    private static ClassFacts lib(String name, @Nullable String superName, Consumer<ClassBuilder> body) {
        return facts(ClassFile.of().build(ClassDesc.ofInternalName(name), c -> {
            c.withFlags(PUBLIC);
            if (superName != null) c.withSuperclass(ClassDesc.ofInternalName(superName));
            body.accept(c);
        }));
    }

    private static void method(ClassBuilder c, String name, MethodTypeDesc type, int flags) {
        c.withMethodBody(name, type, flags, b -> {
            if ((flags & ClassFile.ACC_STATIC) == 0) b.aload(0).pop();
            b.aload((flags & ClassFile.ACC_STATIC) == 0 ? 1 : 0).areturn();
        });
    }

    private static ClassFacts facts(byte[] bytes) {
        return FactsExtractor.extract(bytes);
    }
}
