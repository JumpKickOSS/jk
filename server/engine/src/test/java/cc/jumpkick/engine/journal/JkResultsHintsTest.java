// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Every hint row, rendered: the diagnostic as the compiler worker journals it (header, message,
 * javac's {@code symbol:}/{@code location:} lines) and the {@code →} line that follows it under
 * {@code ## Failures}. A diagnostic outside the table, a warning, and a non-compiler error carry no
 * hint.
 */
class JkResultsHintsTest {

    @Test
    void javac_cannot_find_symbol_names_the_symbol_and_where_javac_looked() {
        String md = render(javac("""
                /ws/app/src/com/acme/Main.java:7:9: error: cannot find symbol
                  symbol:   method missing()
                  location: class Main"""));
        assertThat(md)
                .contains("→ `method missing()` is not declared in `class Main` and not imported: "
                        + "fix the name, add the import, or `jk add` the dependency that provides it.");
        assertThat(code(javac("cannot find symbol"))).isEqualTo("compiler.err.cant.resolve.location");
    }

    @Test
    void javac_package_does_not_exist_names_the_package_and_jk_add() {
        String md = render(
                javac("/ws/app/src/com/acme/Main.java:3:29: error: package com.google.common.collect does not exist"));
        assertThat(md)
                .contains("→ nothing on this module's compile classpath provides package `com.google.common.collect`: "
                        + "`jk add <group:artifact>` the library that ships it, or fix the import.");
        assertThat(code(javac("package a.b does not exist"))).isEqualTo("compiler.err.doesnt.exist");

        String provided = render(javac("""
                /ws/app/src/com/acme/Main.java:3:29: error: package com.google.common.collect does not exist
                  provided by: com.google.guava:guava (library catalog)"""));
        assertThat(provided)
                .contains("→ package `com.google.common.collect` is provided by `com.google.guava:guava` "
                        + "(library catalog): `jk add com.google.guava:guava` in this module, or fix the import.");
    }

    /**
     * A type that left the JDK — {@code java.security.acl.Group} in 14 — reached by a dependency's
     * class hierarchy or an import is not a missing library: the hint names the API, the JDK that
     * removed it and the release to write, since the toolchain JDK cross-compiles for the last LTS
     * that carried it.
     */
    @Test
    void javac_cannot_access_a_removed_jdk_type_names_the_release_that_carries_it() {
        String md = render(keyed("compiler.err.cant.access", """
                /ws/app/src/com/acme/LoginModule.java:40:8: error: cannot access java.security.acl.Group
                  class file for java.security.acl.Group not found"""));
        assertThat(md)
                .contains(
                        "→ `java.security.acl.Group` left the JDK in 14 and this module compiles for a newer release: "
                                + "a class it compiles against still needs it — write `java = 11` in this module, the last LTS "
                                + "that carries it (the toolchain JDK cross-compiles with `--release 11`), or move off the API.");
        assertThat(code(javac("""
                        cannot access java.util.jar.Pack200
                          class file for java.util.jar.Pack200 not found"""))).isEqualTo("compiler.err.cant.access");
    }

    @Test
    void javac_package_that_left_the_jdk_is_the_removed_api_row_not_the_jk_add_row() {
        String md =
                render(javac("/ws/app/src/com/acme/Main.java:3:29: error: package java.security.acl does not exist"));
        assertThat(md)
                .contains(
                        "→ `java.security.acl` left the JDK in 14 and this module compiles for a newer release: "
                                + "a class it compiles against still needs it — write `java = 11` in this module, the last LTS "
                                + "that carries it (the toolchain JDK cross-compiles with `--release 11`), or move off the API.")
                .doesNotContain("jk add");
        String rmi = render(javac("/ws/app/src/Main.java:3:1: error: package java.rmi.activation does not exist"));
        assertThat(rmi).contains("`java.rmi.activation` left the JDK in 17").contains("write `java = 11`");
        String compiler = render(keyed("compiler.err.cant.resolve.location", """
                /ws/app/src/Main.java:9:5: error: cannot find symbol
                  symbol:   class Compiler
                  location: package java.lang"""));
        assertThat(compiler).doesNotContain("left the JDK");
    }

    @Test
    void javac_incompatible_types_names_both_types() {
        String md = render(
                javac("/ws/app/src/Main.java:9:17: error: incompatible types: String cannot be converted to int"));
        assertThat(md)
                .contains("→ the value is `String` where `int` is required: change the declared type, "
                        + "convert the value, or cast when the narrowing is intended.");
        assertThat(code(javac("incompatible types: String cannot be converted to int")))
                .isEqualTo("compiler.err.prob.found.req");
    }

    @Test
    void javac_unreported_exception_names_the_exception_in_both_repairs() {
        String md = render(
                javac(
                        "/ws/app/src/Main.java:12:31: error: unreported exception java.io.IOException; must be caught or declared to be thrown"));
        assertThat(md)
                .contains("→ catch `java.io.IOException` around the call, or add `throws java.io.IOException` "
                        + "to the enclosing method.");
        assertThat(code(javac("unreported exception java.io.IOException; must be caught or declared to be thrown")))
                .isEqualTo("compiler.err.unreported.exception.need.to.catch.or.throw");
    }

    @Test
    void javac_missing_return_uninitialized_variable_and_static_context_have_rows() {
        assertThat(render(javac("/ws/app/src/Main.java:20:5: error: missing return statement")))
                .contains("→ every path out of the method must return a value: add a return after the last branch.");
        assertThat(code(javac("missing return statement"))).isEqualTo("compiler.err.missing.ret.stmt");

        assertThat(render(javac("/ws/app/src/Main.java:22:16: error: variable total might not have been initialized")))
                .contains("→ `total` is read on a path that never assigned it: initialize it at the declaration "
                        + "or on every branch.");
        assertThat(code(javac("variable total might not have been initialized")))
                .isEqualTo("compiler.err.var.might.not.have.been.initialized");

        assertThat(
                        render(
                                javac(
                                        "/ws/app/src/Main.java:30:9: error: non-static method run() cannot be referenced from a static context")))
                .contains("→ `run()` belongs to an instance: call it on one, or make it static when it uses "
                        + "no instance state.");
        assertThat(code(javac("non-static variable count cannot be referenced from a static context")))
                .isEqualTo("compiler.err.non-static.cant.be.ref");
    }

    @Test
    void kotlinc_unresolved_reference_in_either_dialect_names_the_reference() {
        String k1 = render(kotlinc("/ws/app/src/Main.kt:4:5: error: unresolved reference: missing"));
        String k2 = render(kotlinc("e: file:///ws/app/src/Main.kt:4:5 Unresolved reference 'missing'."));
        String hint = "→ `missing` is not declared, imported or on this module's compile classpath: "
                + "fix the name, add the import, or `jk add` the dependency that provides it.";
        assertThat(k1).contains(hint);
        assertThat(k2).contains(hint);
        assertThat(code(kotlinc("unresolved reference: missing"))).isEqualTo("UNRESOLVED_REFERENCE");
    }

    @Test
    void kotlinc_type_mismatch_in_either_dialect_has_one_row() {
        String hint = "→ the value's type is not the one the declaration wants: change the declared type, "
                + "convert the value, or cast with `as` when the narrowing is intended.";
        assertThat(
                        render(
                                kotlinc(
                                        "/ws/app/src/Main.kt:6:13: error: type mismatch: inferred type is String but Int was expected")))
                .contains(hint);
        assertThat(
                        render(
                                kotlinc(
                                        "e: file:///ws/app/src/Main.kt:6:13 Argument type mismatch: actual type is 'String', but 'Int' was expected.")))
                .contains(hint);
        assertThat(
                        render(
                                kotlinc(
                                        "e: file:///ws/app/src/Main.kt:6:13 Initializer type mismatch: expected 'Int', actual 'String'.")))
                .contains(hint);
        assertThat(code(kotlinc("type mismatch: inferred type is String but Int was expected")))
                .isEqualTo("TYPE_MISMATCH");
    }

    @Test
    void kotlinc_unsafe_call_and_missing_argument_have_rows() {
        assertThat(
                        render(
                                kotlinc(
                                        "/ws/app/src/Main.kt:8:10: error: Only safe (?.) or non-null asserted (!!.) calls are allowed on a nullable receiver of type String?")))
                .contains("→ the receiver may be null: call through `?.`, check for null first, or make the type "
                        + "non-null where the value is produced.");
        assertThat(
                        code(
                                kotlinc(
                                        "Only safe (?.) or non-null asserted (!!.) calls are allowed on a nullable receiver of type 'String?'.")))
                .isEqualTo("UNSAFE_CALL");

        assertThat(render(kotlinc("/ws/app/src/Main.kt:9:5: error: No value passed for parameter 'name'.")))
                .contains("→ pass an argument for `name`, or give the parameter a default value.");
        assertThat(code(kotlinc("no value passed for parameter name"))).isEqualTo("NO_VALUE_FOR_PARAMETER");
    }

    @Test
    void a_diagnostic_outside_the_table_a_warning_and_another_tool_carry_no_hint() {
        assertThat(render(javac("/ws/app/src/Main.java:3:1: error: class, interface, enum, or record expected")))
                .doesNotContain("→ ");
        BuildRecord.Diag warning = new BuildRecord.Diag(
                "warning",
                "/ws/app",
                "compile-java",
                "javac",
                "cannot find symbol",
                null,
                null,
                "g:app",
                null,
                null,
                null,
                null);
        assertThat(JkResultsHints.forDiag(warning)).isNull();
        BuildRecord.Diag guard = new BuildRecord.Diag(
                "error",
                "/ws/app",
                "guard-tree",
                "file-size",
                "cannot find symbol",
                null,
                null,
                "g:app",
                null,
                null,
                null,
                null);
        assertThat(JkResultsHints.forDiag(guard)).isNull();
    }

    @Test
    void javacs_key_selects_the_row_whatever_the_message_says() {
        // A localized javac: the message shape matches no row, the key still does.
        BuildRecord.Diag localized = keyed("compiler.err.cant.resolve.location", """
                /ws/app/src/Main.java:7:9: error: Symbol nicht gefunden
                  Symbol:   Methode missing()
                  Ort: Klasse Main""");
        assertThat(code(localized)).isEqualTo("compiler.err.cant.resolve.location");
        assertThat(render(localized)).contains("→ the name is not declared here and not imported:");

        BuildRecord.Diag lossy = keyed(
                "compiler.err.prob.found.req",
                "/ws/app/src/Main.java:9:17: error: incompatible types: possible lossy conversion from double to int");
        assertThat(code(lossy)).isEqualTo("compiler.err.prob.found.req");
        assertThat(render(lossy)).contains("→ the value's type is not the one the declaration requires:");

        BuildRecord.Diag pkg = keyed(
                "compiler.err.doesnt.exist",
                "/ws/app/src/Main.java:3:29: error: package com.google.common.collect does not exist");
        assertThat(render(pkg)).contains("provides package `com.google.common.collect`");
    }

    @Test
    void a_key_the_table_lacks_falls_back_to_the_message_shape_and_a_keyless_message_is_matched_by_shape() {
        BuildRecord.Diag unknownKey = keyed("compiler.err.something.else", "cannot find symbol\n  symbol: x");
        assertThat(code(unknownKey)).isEqualTo("compiler.err.cant.resolve.location");
        assertThat(code(keyed("compiler.err.already.defined", "class A is already defined in package a")))
                .isNull();
        assertThat(code(javac("missing return statement"))).isEqualTo("compiler.err.missing.ret.stmt");
    }

    @Test
    void the_first_line_is_the_compiler_text_whatever_header_it_wears() {
        assertThat(JkResultsHints.firstLine("cannot find symbol\n  symbol: x")).isEqualTo("cannot find symbol");
        assertThat(JkResultsHints.firstLine("/ws/A.java:3:4: error: cannot find symbol"))
                .isEqualTo("cannot find symbol");
        assertThat(JkResultsHints.firstLine("src/B.kt:2:5: error: unresolved reference: x"))
                .isEqualTo("unresolved reference: x");
        assertThat(JkResultsHints.firstLine("e: file:///ws/B.kt:2:5 Unresolved reference 'x'."))
                .endsWith("Unresolved reference 'x'.");
    }

    private static @Nullable String code(BuildRecord.Diag d) {
        JkResultsHints.Hint hint = JkResultsHints.forDiag(d);
        return hint == null ? null : hint.code();
    }

    private static BuildRecord.Diag keyed(String key, String message) {
        return new BuildRecord.Diag(
                "error",
                "/ws/app",
                "compile-java",
                "javac",
                message,
                null,
                null,
                "g:app",
                null,
                null,
                null,
                null,
                "",
                0,
                0,
                0,
                List.of(),
                0,
                key);
    }

    private static BuildRecord.Diag javac(String message) {
        return new BuildRecord.Diag(
                "error", "/ws/app", "compile-java", "javac", message, null, null, "g:app", null, null, null, null);
    }

    private static BuildRecord.Diag kotlinc(String message) {
        return new BuildRecord.Diag(
                "error", "/ws/app", "compile-kotlin", "kotlinc", message, null, null, "g:app", null, null, null, null);
    }

    private static String render(BuildRecord.Diag d) {
        BuildRecord r = new BuildRecord(
                "id",
                3,
                BuildRecord.SCHEMA,
                "build",
                "/ws",
                "g:ws",
                "pid",
                1_000,
                1_100,
                100,
                false,
                false,
                1,
                "9.9",
                null,
                List.of(),
                List.of(new BuildRecord.Task(requireNonNull(d.step()), "compile", "FAIL", 80, 0)),
                List.of(d),
                "cli",
                null,
                null,
                null,
                false,
                null,
                0L,
                null,
                List.of());
        return JkResultsMarkdown.render(r);
    }
}
