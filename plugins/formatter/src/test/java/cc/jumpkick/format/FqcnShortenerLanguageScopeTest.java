// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.format;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The FQCN pass runs on Java and nothing else, and this is the guard on that boundary rather than a
 * preference to be tidied away later.
 *
 * <p>{@link JavaText#blankNonCode} implements Java's lexeme set. The other three languages differ in
 * ways that make it rewrite the inside of string literals: Groovy has {@code '''} blocks (the blanker
 * reads {@code '} as a char literal, so the first apostrophe inside closes it) and slashy {@code /…/}
 * regex literals it does not recognise at all; Kotlin and Scala have {@code import … as Alias} and
 * {@code x._} / {@code x.{A, B}} forms whose bindings it cannot read, so it cannot tell which simple
 * names are already taken.
 *
 * <p>Java is also the only one of the four whose Spotless chain contains an import-repair step, so it
 * is the only one where a mistake made here would be noticed downstream instead of written to disk and
 * stamped as settled. Widening this needs a real lexer per language, not a wider regex — see
 * {@code CodeFormatter.formatOne}.
 */
class FqcnShortenerLanguageScopeTest {

    /** The shortener itself is language-parameterised, and Java shortens. */
    @Test
    void java_is_shortened(@TempDir Path tmp) throws Exception {
        assertThat(shortenAs(tmp, FqcnShortener.Syntax.JAVA, """
                package demo;

                public class Uses {
                    String go() {
                        return cc.jumpkick.foo.Bar.hi();
                    }
                }
                """)).isTrue();
    }

    /**
     * A Groovy triple-quoted string is a string. The blanker treats {@code '} as a char literal, so
     * the apostrophe in "Don't" closes it early and the rest of the literal is read as code — which is
     * exactly why Groovy does not go through this pass in {@code CodeFormatter}.
     */
    @Test
    void the_blanker_still_mis_lexes_a_groovy_triple_quoted_string() {
        String src = """
                def s = '''Don't reference cc.jumpkick.foo.Bar directly.'''
                """;
        String blanked = JavaText.blankNonCode(src);
        assertThat(blanked)
                .as("if this ever stops containing the FQCN, Groovy's lexing was fixed and the "
                        + "Kind.JAVA restriction in CodeFormatter.formatOne can be revisited")
                .contains("cc.jumpkick.foo.Bar");
    }

    /** Likewise a Groovy slashy string, which the blanker does not recognise at all. */
    @Test
    void the_blanker_still_mis_lexes_a_groovy_slashy_string() {
        assertThat(JavaText.blankNonCode("def p = ~/cc.jumpkick.foo.Bar/\n")).contains("cc.jumpkick.foo.Bar");
    }

    /** A Kotlin alias binds a different simple name than the type's own, which this pass cannot model. */
    @Test
    void an_alias_import_is_treated_as_opaque(@TempDir Path tmp) throws Exception {
        // `Bar` is bound to something else by the alias line, so nothing may claim that name.
        boolean changed = shortenAs(tmp, FqcnShortener.Syntax.KOTLIN, """
                import other.pkg.Thing as Bar

                class Uses {
                    val a: Bar? = null
                    val b: cc.jumpkick.foo.Bar? = null
                }
                """);
        assertThat(changed)
                .as("an `as` alias is an import whose bindings cannot be enumerated, so shortening must refuse")
                .isFalse();
    }

    private static boolean shortenAs(Path tmp, FqcnShortener.Syntax syntax, String source) throws Exception {
        Path bar = tmp.resolve("Bar.java");
        Files.writeString(bar, """
                package cc.jumpkick.foo;

                public class Bar {
                    public static String hi() {
                        return "foo";
                    }
                }
                """);
        return FqcnShortener.shorten(source, TypeIndex.scan(List.of(bar), false), syntax)
                .changed();
    }
}
