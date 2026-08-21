// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.giter8;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.repo.MavenMetadata;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Giter8ApplyTest {

    @Test
    void substitutes_paths_and_content(@TempDir Path tmp) throws Exception {
        Path template = g8(tmp, "name=demo\npackage=com.demo\norganization=com.demo\n");
        Path g8 = template.resolve("src/main/g8");
        Files.createDirectories(g8.resolve("src/$package$"));
        Files.writeString(g8.resolve("jk.toml"), "name = \"$name$\"\ngroup = \"$organization$\"\n");
        Files.writeString(g8.resolve("src/$package$/Main.java"), "package $package$;\n// $name$\n");

        Path dest = tmp.resolve("out");
        int n = Giter8Apply.apply(template, dest, Map.of("name", "widget"));
        assertThat(n).isEqualTo(2);
        assertThat(dest.resolve("jk.toml")).content().contains("name = \"widget\"");
        assertThat(dest.resolve("src/com/demo/Main.java")).content().contains("package com.demo;");
    }

    @Test
    void binary_and_non_utf8_files_pass_through_byte_for_byte(@TempDir Path tmp) throws Exception {
        Path template = g8(tmp, "name=demo\n");
        Path g8 = template.resolve("src/main/g8");
        Files.createDirectories(g8);
        Files.writeString(g8.resolve("jk.toml"), "name = \"$name$\"\n");
        byte[] binary = {0x50, 0x4B, 0x00, 0x01, (byte) 0xFF};
        Files.write(g8.resolve("logo.bin"), binary);
        // ISO-8859-1 "café $name$" — invalid UTF-8; a lossy decode would corrupt é AND render $name$.
        byte[] latin1 = "café $name$\n".getBytes(StandardCharsets.ISO_8859_1);
        Files.write(g8.resolve("README"), latin1);

        Path dest = tmp.resolve("out");
        Giter8Apply.apply(template, dest, Map.of());

        assertThat(Files.readAllBytes(dest.resolve("logo.bin"))).isEqualTo(binary);
        assertThat(Files.readAllBytes(dest.resolve("README"))).isEqualTo(latin1);
    }

    @Test
    void root_layout_template_excludes_git_and_metadata(@TempDir Path tmp) throws Exception {
        // A cloned root-as-content template: .git (with ST-hostile content) and .jk-template.toml
        // are template plumbing, never project content.
        Path template = tmp.resolve("root.g8");
        Files.createDirectories(template.resolve(".git/refs"));
        Files.writeString(template.resolve(".git/packed-refs"), "ref: $broken\n");
        Files.writeString(template.resolve(".git/HEAD"), "ref: refs/heads/main\n");
        Files.createDirectories(template);
        Files.writeString(template.resolve("default.properties"), "name=demo\n");
        Files.writeString(template.resolve(".jk-template.toml"), "language = \"java\"\n");
        Files.writeString(template.resolve("jk.toml"), "name = \"$name$\"\n");

        Path dest = tmp.resolve("out");
        int n = Giter8Apply.apply(template, dest, Map.of("name", "widget"));

        assertThat(n).isEqualTo(1);
        assertThat(dest.resolve("jk.toml")).content().contains("widget");
        assertThat(dest.resolve(".git")).doesNotExist();
        assertThat(dest.resolve(".jk-template.toml")).doesNotExist();
    }

    @Test
    void overrides_apply_before_derived_properties_expand(@TempDir Path tmp) throws Exception {
        // Canonical Giter8 idiom: package derives from organization+name. The user's overrides
        // must feed the derivation — expanding defaults first freezes package to com.example.*.
        Path template = g8(tmp, "name=demo\norganization=com.example\npackage=$organization$.$name$\n");
        Path g8 = template.resolve("src/main/g8");
        Files.createDirectories(g8);
        Files.writeString(g8.resolve("jk.toml"), "name = \"$name$\"\n");
        Files.writeString(g8.resolve("App.java"), "package $package$;\n");

        Path dest = tmp.resolve("out");
        Giter8Apply.apply(template, dest, Map.of("name", "widget", "organization", "org.acme"));

        assertThat(dest.resolve("App.java")).content().contains("package org.acme.widget;");
    }

    @Test
    void template_property_cannot_escape_the_destination(@TempDir Path tmp) throws Exception {
        Path template = g8(tmp, "name=demo\nevil=../../../pwned\n");
        Path g8 = template.resolve("src/main/g8");
        Files.createDirectories(g8.resolve("$evil$"));
        Files.writeString(g8.resolve("$evil$/payload.txt"), "owned\n");

        Path dest = tmp.resolve("proj/out");
        Files.createDirectories(dest);
        assertThatThrownBy(() -> Giter8Apply.apply(template, dest, Map.of()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("escapes the project directory");
        assertThat(tmp.resolve("pwned")).doesNotExist();
    }

    @Test
    void symlinked_template_entries_are_skipped_not_dereferenced(@TempDir Path tmp) throws Exception {
        Path secret = tmp.resolve("id_ed25519");
        Files.writeString(secret, "PRIVATE KEY MATERIAL\n");
        Path template = g8(tmp, "name=demo\n");
        Path g8 = template.resolve("src/main/g8");
        Files.writeString(g8.resolve("jk.toml"), "name = \"$name$\"\n");
        Files.createSymbolicLink(g8.resolve("secrets.txt"), secret);

        Path dest = tmp.resolve("out");
        Giter8Apply.apply(template, dest, Map.of());
        assertThat(dest.resolve("jk.toml")).exists();
        assertThat(dest.resolve("secrets.txt")).doesNotExist();
    }

    @Test
    void formatters_camel_and_normalize(@TempDir Path tmp) throws Exception {
        Path template = g8(tmp, "name=My Project\n");
        Path g8 = template.resolve("src/main/g8");
        Files.writeString(
                g8.resolve("$name__Camel$.txt"),
                "C=$name;format=\"Camel\"$ n=$name;format=\"normalize\"$ c=$name;format=\"camel\"$\n");
        Path dest = tmp.resolve("out");
        Giter8Apply.apply(template, dest, Map.of());
        assertThat(dest.resolve("MyProject.txt"))
                .content()
                .contains("C=MyProject")
                .contains("n=my-project")
                .contains("c=myProject");
    }

    @Test
    void conditionals_in_content_and_paths(@TempDir Path tmp) throws Exception {
        Path template = g8(tmp, "name=demo\nscala212=yes\njvm=no\n");
        Path g8 = template.resolve("src/main/g8");
        Files.writeString(g8.resolve("ver.txt"), """
                $if(scala212.truthy)$
                two-twelve
                $else$
                other
                $endif$
                """);
        Files.createDirectories(g8.resolve("$if(jvm.truthy)$jvm$endif$"));
        Files.writeString(g8.resolve("$if(jvm.truthy)$jvm$endif$/skip-me.txt"), "nope\n");
        Files.createDirectories(g8.resolve("keep"));
        Files.writeString(g8.resolve("keep/ok.txt"), "yes\n");

        Path dest = tmp.resolve("out");
        Giter8Apply.apply(template, dest, Map.of());
        assertThat(dest.resolve("ver.txt")).content().contains("two-twelve").doesNotContain("other");
        assertThat(dest.resolve("jvm/skip-me.txt")).doesNotExist();
        assertThat(dest.resolve("keep/ok.txt")).content().isEqualTo("yes\n");
    }

    @Test
    void flatten_dot_directory_segment(@TempDir Path tmp) throws Exception {
        Path template = g8(tmp, "name=demo\ncond=no\n");
        Path g8 = template.resolve("src/main/g8");
        Files.createDirectories(g8.resolve("parent/$if(cond.truthy)$skip$else$.$endif$"));
        Files.writeString(g8.resolve("parent/$if(cond.truthy)$skip$else$.$endif$/child.txt"), "flat\n");
        Path dest = tmp.resolve("out");
        Giter8Apply.apply(template, dest, Map.of());
        assertThat(dest.resolve("parent/child.txt")).content().isEqualTo("flat\n");
        assertThat(dest.resolve("parent/skip/child.txt")).doesNotExist();
    }

    @Test
    void comments_are_stripped(@TempDir Path tmp) throws Exception {
        Path template = g8(tmp, "name=demo\n");
        Path g8 = template.resolve("src/main/g8");
        Files.writeString(g8.resolve("a.txt"), "$! hidden !$visible $name$\n");
        Path dest = tmp.resolve("out");
        Giter8Apply.apply(template, dest, Map.of());
        assertThat(dest.resolve("a.txt")).content().isEqualTo("visible demo\n");
    }

    @Test
    void verbatim_skips_substitution(@TempDir Path tmp) throws Exception {
        Path template = g8(tmp, "name=demo\nverbatim=*.html\n");
        Path g8 = template.resolve("src/main/g8");
        Files.writeString(g8.resolve("page.html"), "Hi $name$\n");
        Files.writeString(g8.resolve("note.txt"), "Hi $name$\n");
        Path dest = tmp.resolve("out");
        Giter8Apply.apply(template, dest, Map.of());
        assertThat(dest.resolve("page.html")).content().isEqualTo("Hi $name$\n");
        assertThat(dest.resolve("note.txt")).content().isEqualTo("Hi demo\n");
    }

    @Test
    void property_chaining(@TempDir Path tmp) throws Exception {
        Path template = g8(tmp, "github_id=n8han\nproject_url=https://github.com/$github_id$\n");
        Path g8 = template.resolve("src/main/g8");
        Files.writeString(g8.resolve("u.txt"), "$project_url$\n");
        Path dest = tmp.resolve("out");
        Giter8Apply.apply(template, dest, Map.of());
        assertThat(dest.resolve("u.txt")).content().isEqualTo("https://github.com/n8han\n");
    }

    @Test
    void maven_expr_uses_lookup(@TempDir Path tmp) throws Exception {
        Path template = g8(tmp, "lib_version=maven(org.example, widget, stable)\n");
        Path g8 = template.resolve("src/main/g8");
        Files.writeString(g8.resolve("v.txt"), "$lib_version$\n");
        Path dest = tmp.resolve("out");
        Giter8Apply.apply(template, dest, Map.of(), (g, a, stable) -> {
            assertThat(g).isEqualTo("org.example");
            assertThat(a).isEqualTo("widget");
            assertThat(stable).isTrue();
            return "9.9.9";
        });
        assertThat(dest.resolve("v.txt")).content().isEqualTo("9.9.9\n");
    }

    @Test
    void maven_expr_without_lookup_fails(@TempDir Path tmp) throws Exception {
        Path template = g8(tmp, "lib_version=maven(org.example, widget)\n");
        Path g8 = template.resolve("src/main/g8");
        Files.writeString(g8.resolve("v.txt"), "$lib_version$\n");
        assertThatThrownBy(() -> Giter8Apply.apply(template, tmp.resolve("out"), Map.of()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("maven()");
    }

    @Test
    void maven_offline_lookup_fails() {
        MavenVersionLookup lookup = Giter8Maven.central(true);
        assertThatThrownBy(() -> lookup.latest("g", "a", false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("offline");
    }

    @Test
    void maven_pick_prefers_release_when_stable() {
        var md = new MavenMetadata("g", "a", List.of("1.0.0", "2.0.0-RC1", "2.0.0"), "2.0.0-RC1", "1.0.0");
        assertThat(Giter8Maven.pick(md, true)).isEqualTo("1.0.0");
        assertThat(Giter8Maven.pick(md, false)).isEqualTo("2.0.0-RC1");
    }

    @Test
    void dual_layout_simple_vs_traditional(@TempDir Path tmp) throws Exception {
        Path template = g8(tmp, "name=demo\npackage=com.demo\n");
        Path g8 = template.resolve("src/main/g8");
        String src = "src/$if(simple.truthy)$.$else$main$endif$/$if(simple.truthy)$.$else$java$endif$/$package$";
        Files.createDirectories(g8.resolve(src));
        Files.writeString(g8.resolve(src + "/Main.java"), "package $package$;\n");
        String res = "$if(simple.truthy)$resources$else$src/main/resources$endif$";
        Files.createDirectories(g8.resolve(res));
        Files.writeString(g8.resolve(res + "/app.txt"), "ok\n");
        String test = "$if(simple.truthy)$test/src$else$src/test/java$endif$/$package$";
        Files.createDirectories(g8.resolve(test));
        Files.writeString(g8.resolve(test + "/MainTest.java"), "package $package$;\n");

        Path trad = tmp.resolve("trad");
        Giter8Apply.apply(template, trad, Map.of());
        assertThat(trad.resolve("src/main/java/com/demo/Main.java")).exists();
        assertThat(trad.resolve("src/main/resources/app.txt")).exists();
        assertThat(trad.resolve("src/test/java/com/demo/MainTest.java")).exists();

        Path simple = tmp.resolve("simple");
        Giter8Apply.apply(template, simple, Map.of("simple", "yes"));
        assertThat(simple.resolve("src/com/demo/Main.java")).exists();
        assertThat(simple.resolve("resources/app.txt")).exists();
        assertThat(simple.resolve("test/src/com/demo/MainTest.java")).exists();
        assertThat(simple.resolve("src/main/java")).doesNotExist();
    }

    @Test
    void does_not_nest_name_directory(@TempDir Path tmp) throws Exception {
        Path template = g8(tmp, "name=My App\n");
        Path g8 = template.resolve("src/main/g8");
        Files.writeString(g8.resolve("jk.toml"), "name = \"$name$\"\n");
        Path dest = tmp.resolve("out");
        Giter8Apply.apply(template, dest, Map.of());
        assertThat(dest.resolve("jk.toml")).exists();
        assertThat(dest.resolve("my-app/jk.toml")).doesNotExist();
    }

    private static Path g8(Path tmp, String props) throws IOException {
        Path template = Files.createTempDirectory(tmp, "t");
        Path g8 = template.resolve("src/main/g8");
        Files.createDirectories(g8);
        Files.writeString(template.resolve("default.properties"), props);
        return template;
    }
}
