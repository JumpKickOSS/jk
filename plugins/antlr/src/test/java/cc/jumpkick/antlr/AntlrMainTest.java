// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.antlr;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The main over the real ANTLR tool (on this test's classpath): a lexer and parser pair in a
 * grammar subdirectory generate into that directory's package, an import under {@code lib} is
 * read but never generated from, and a grammar error is a non-zero exit.
 */
class AntlrMainTest {

    private static final String LEXER = """
            lexer grammar LabelLexer;
            import Common;
            AND : '&&';
            OR : '||';
            """;

    private static final String PARSER = """
            parser grammar LabelParser;
            options { tokenVocab = LabelLexer; }
            expr : term ((AND | OR) term)* ;
            term : ATOM ;
            """;

    private static final String COMMON = """
            lexer grammar Common;
            ATOM : [a-zA-Z0-9_]+ ;
            WS : [ \\t\\r\\n]+ -> skip ;
            """;

    @Test
    void a_grammar_directory_generates_into_its_package(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src/main/antlr4");
        write(src.resolve("hudson/model/labels/LabelLexer.g4"), LEXER);
        write(src.resolve("hudson/model/labels/LabelParser.g4"), PARSER);
        write(src.resolve("imports/Common.g4"), COMMON);
        Path out = Files.createDirectories(tmp.resolve("out"));

        int exit = AntlrMain.run(new String[] {
            "--out",
            out.toString(),
            "--src",
            src.toString(),
            "--lib",
            src.resolve("imports").toString(),
            "--visitor",
            src.resolve("hudson/model/labels/LabelLexer.g4").toString(),
            src.resolve("hudson/model/labels/LabelParser.g4").toString(),
            src.resolve("imports/Common.g4").toString(),
        });

        assertThat(exit).isZero();
        Path pkg = out.resolve("hudson/model/labels");
        assertThat(pkg.resolve("LabelLexer.java")).content().contains("package hudson.model.labels;");
        assertThat(pkg.resolve("LabelParser.java")).content().contains("package hudson.model.labels;");
        assertThat(pkg.resolve("LabelParserVisitor.java")).isRegularFile();
        assertThat(pkg.resolve("LabelParserListener.java")).isRegularFile();
        assertThat(out.resolve("Common.java")).doesNotExist();
        assertThat(out.resolve("imports")).doesNotExist();
    }

    @Test
    void a_package_option_replaces_the_directory_package(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("grammars");
        write(
                src.resolve("Hello.g4"),
                "grammar Hello;\nr : 'hello' ID ;\nID : [a-z]+ ;\nWS : [ \\t\\r\\n]+ -> skip ;\n");
        Path out = Files.createDirectories(tmp.resolve("out"));

        int exit = AntlrMain.run(new String[] {
            "--out",
            out.toString(),
            "--src",
            src.toString(),
            "--package",
            "com.acme.hello",
            "--no-listener",
            src.resolve("Hello.g4").toString(),
        });

        assertThat(exit).isZero();
        assertThat(out.resolve("HelloParser.java")).content().contains("package com.acme.hello;");
        assertThat(out.resolve("HelloListener.java")).doesNotExist();
    }

    @Test
    void a_grammar_error_is_a_failed_run(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("grammars");
        write(src.resolve("Broken.g4"), "grammar Broken;\nr : 'a' undefinedRule ;\n");
        Path out = Files.createDirectories(tmp.resolve("out"));

        int exit = AntlrMain.run(new String[] {
            "--out",
            out.toString(),
            "--src",
            src.toString(),
            src.resolve("Broken.g4").toString()
        });

        assertThat(exit).isNotZero();
    }

    @Test
    void grammars_group_by_their_directory_and_imports_are_left_out() {
        Path src = Path.of("/m/src/main/antlr4").toAbsolutePath();
        Map<String, List<String>> folders = AntlrMain.byFolder(
                src,
                src.resolve("imports"),
                List.of(
                        src.resolve("a/b/One.g4"),
                        src.resolve("Root.g4"),
                        src.resolve("a/b/Two.g4"),
                        src.resolve("imports/Common.g4")));

        assertThat(folders)
                .containsExactly(
                        Map.entry("a/b", List.of("a/b/One.g4", "a/b/Two.g4")), Map.entry("", List.of("Root.g4")));
    }

    private static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
