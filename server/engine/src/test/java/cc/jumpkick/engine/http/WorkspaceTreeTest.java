// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.http.WorkspaceTree.Entry;
import cc.jumpkick.engine.http.WorkspaceTree.ListResult;
import cc.jumpkick.engine.http.WorkspaceTree.Page;
import cc.jumpkick.testing.Symlinks;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceTreeTest {

    private static Page page(Path root, String rel) {
        return page(root, rel, null, 0);
    }

    private static Page page(Path root, String rel, @Nullable String cursor, int limit) {
        ListResult r = WorkspaceTree.list(root, rel, cursor, limit);
        assertThat(r).isInstanceOf(ListResult.Ok.class);
        return ((ListResult.Ok) r).page();
    }

    private static List<String> names(Page p) {
        return p.entries().stream().map(Entry::name).toList();
    }

    private static Entry entry(Page p, String name) {
        return p.entries().stream()
                .filter(e -> e.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void a_directory_lists_its_own_children_dirs_first_case_insensitively(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("src/deep/er"));
        Files.createDirectories(root.resolve("Docs"));
        Files.writeString(root.resolve("Docs/a.md"), "# a");
        Files.writeString(root.resolve("Docs/b.md"), "# b");
        Files.writeString(root.resolve("src/deep/er/X.java"), "class X {}");
        Files.writeString(root.resolve("src/Y.java"), "class Y {}");
        Files.writeString(root.resolve("jk.toml"), "name = \"demo\"\n");
        Files.writeString(root.resolve("README.md"), "# demo\n");
        Files.writeString(root.resolve("build.gradle"), "plugins {}\n");

        Page top = page(root, "");
        assertThat(top.path()).isEmpty();
        assertThat(names(top)).containsExactly("Docs", "src", "build.gradle", "jk.toml", "README.md");
        assertThat(top.total()).isEqualTo(5);
        assertThat(top.next()).isNull();
        assertThat(entry(top, "build.gradle").lang()).isEqualTo("groovy");
        assertThat(entry(top, "src").dir()).isTrue();
        assertThat(entry(top, "src").lang()).isNull();

        Page src = page(root, "src");
        assertThat(src.entries()).extracting(Entry::path).containsExactly("src/deep/er", "src/Y.java");
        assertThat(names(src)).containsExactly("deep/er", "Y.java");
    }

    @Test
    void a_java_package_chain_folds_into_one_row_keyed_by_its_deepest_directory(@TempDir Path root) throws Exception {
        Path pkg = Files.createDirectories(root.resolve("core/src/main/java/cc/jumpkick/config"));
        Files.writeString(pkg.resolve("A.java"), "class A {}");
        Files.writeString(pkg.resolve("B.java"), "class B {}");
        Files.writeString(root.resolve("core/jk.toml"), "name = \"core\"\n");

        Page core = page(root, "core");
        Entry chain = entry(core, "src/main/java/cc/jumpkick/config");
        assertThat(chain.path()).isEqualTo("core/src/main/java/cc/jumpkick/config");
        assertThat(chain.empty()).isFalse();
        assertThat(names(page(root, chain.path()))).containsExactly("A.java", "B.java");

        // The checkout root's own children fold too: `core` holds two entries, so it stays one segment.
        assertThat(names(page(root, ""))).containsExactly("core");
    }

    @Test
    void an_empty_directory_says_so(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("empty/inner"));
        Files.createDirectories(root.resolve("hidden-only"));
        Files.writeString(root.resolve("hidden-only/.keep"), "");
        Page top = page(root, "");
        assertThat(entry(top, "empty/inner").empty()).isTrue();
        assertThat(entry(top, "hidden-only").empty()).isTrue();
    }

    @Test
    void a_big_directory_comes_in_pages_behind_a_cursor(@TempDir Path root) throws Exception {
        Path logs = Files.createDirectories(root.resolve("logs"));
        for (int i = 0; i < 1205; i++) Files.writeString(logs.resolve(String.format("run-%04d.log", i)), "x");

        Page first = page(root, "logs");
        assertThat(first.entries()).hasSize(WorkspaceTree.DEFAULT_PAGE);
        assertThat(first.total()).isEqualTo(1205);
        assertThat(first.next()).isEqualTo("500");
        assertThat(first.entries().getFirst().name()).isEqualTo("run-0000.log");

        List<String> all = new ArrayList<>(names(first));
        String cursor = first.next();
        while (cursor != null) {
            Page next = page(root, "logs", cursor, 0);
            all.addAll(names(next));
            cursor = next.next();
        }
        assertThat(all).hasSize(1205).doesNotHaveDuplicates().endsWith("run-1204.log");

        assertThat(page(root, "logs", null, 5000).entries()).hasSize(WorkspaceTree.MAX_PAGE);
        assertThat(WorkspaceTree.list(root, "logs", "nope", 0)).isInstanceOf(ListResult.BadRequest.class);
        assertThat(WorkspaceTree.list(root, "logs", "-1", 0)).isInstanceOf(ListResult.BadRequest.class);
    }

    @Test
    void build_output_is_listed_and_marked(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("jk.toml"), "name = \"demo\"\n");
        Files.createDirectories(root.resolve("target/classes/a"));
        Files.writeString(root.resolve("target/classes/a/A.class"), "x");
        Files.writeString(root.resolve("target/classes/a/B.class"), "x");
        Files.writeString(root.resolve("target/report.md"), "# r");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/A.java"), "class A {}");

        Page top = page(root, "");
        assertThat(entry(top, "target").output()).isTrue();
        assertThat(entry(top, "src").output()).isFalse();
        Page target = page(root, "target");
        assertThat(target.entries()).allMatch(Entry::output);
        Page classes = page(root, entry(target, "classes/a").path());
        assertThat(classes.entries()).extracting(Entry::lang).containsOnly("binary");
    }

    @Test
    void hidden_names_node_modules_and_key_material_never_show(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve(".git"));
        Files.createDirectories(root.resolve("node_modules/x"));
        Files.writeString(root.resolve(".env"), "SECRET=1");
        Files.writeString(root.resolve("tls.key"), "k");
        Files.writeString(root.resolve("id_rsa"), "k");
        Files.writeString(root.resolve("id_rsa.pub"), "ssh-rsa AAAA");
        Files.writeString(root.resolve("app.js"), "export {}");

        assertThat(names(page(root, ""))).containsExactly("app.js", "id_rsa.pub");
        assertThat(WorkspaceTree.list(root, ".git", null, 0)).isInstanceOf(ListResult.NotFound.class);
        assertThat(WorkspaceTree.list(root, "node_modules", null, 0)).isInstanceOf(ListResult.NotFound.class);
        assertThat(WorkspaceTree.list(root, "../x", null, 0)).isInstanceOf(ListResult.BadRequest.class);
        assertThat(WorkspaceTree.list(root, "missing", null, 0)).isInstanceOf(ListResult.NotFound.class);
        assertThat(WorkspaceTree.list(root, "app.js", null, 0)).isInstanceOf(ListResult.NotFound.class);
    }

    @Test
    void symlinks_count_as_their_target_only_inside_the_checkout(@TempDir Path root, @TempDir Path outside)
            throws Exception {
        Files.writeString(outside.resolve("Secret.java"), "class Secret {}");
        Files.createDirectories(root.resolve("real"));
        Files.writeString(root.resolve("real/A.java"), "class A {}");
        Files.writeString(root.resolve("real/B.java"), "class B {}");
        Symlinks.create(root.resolve("linkdir"), root.resolve("real"));
        Symlinks.create(root.resolve("esc"), outside);
        Symlinks.create(root.resolve("Leak.java"), outside.resolve("Secret.java"));
        // A link back to the root: folding and listing must not loop on it.
        Symlinks.create(root.resolve("real/loop"), root);

        Page top = page(root, "");
        assertThat(names(top)).containsExactly("linkdir", "real");
        assertThat(entry(top, "linkdir").dir()).isTrue();
        assertThat(names(page(root, "linkdir"))).containsExactly("loop", "A.java", "B.java");
        assertThat(WorkspaceTree.list(root, "esc", null, 0)).isInstanceOf(ListResult.NotFound.class);
    }

    @Test
    void find_matches_every_term_ranks_name_hits_first_and_caps(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("jk.toml"), "name = \"demo\"\n");
        Path pkg = Files.createDirectories(root.resolve("src/main/java/demo/pricing"));
        Files.writeString(pkg.resolve("Rules.java"), "class Rules {}");
        Files.writeString(pkg.resolve("PricingRules.java"), "class PricingRules {}");
        Files.createDirectories(root.resolve("target/classes/demo/pricing"));
        Files.writeString(root.resolve("target/classes/demo/pricing/PricingRules.class"), "x");
        Files.writeString(root.resolve(".env"), "PRICING=1");

        WorkspaceTree.Matches m = WorkspaceTree.find(root, "pricing rules", 0);
        assertThat(m.files())
                .extracting(WorkspaceTree.Found::path)
                .containsExactly(
                        "src/main/java/demo/pricing/PricingRules.java",
                        "target/classes/demo/pricing/PricingRules.class",
                        "src/main/java/demo/pricing/Rules.java");
        assertThat(m.more()).isFalse();
        assertThat(m.partial()).isFalse();

        WorkspaceTree.Matches capped = WorkspaceTree.find(root, "pricing", 1);
        assertThat(capped.files()).hasSize(1);
        assertThat(capped.more()).isTrue();
        assertThat(capped.files().getFirst().path()).isEqualTo("src/main/java/demo/pricing/PricingRules.java");

        assertThat(WorkspaceTree.find(root, "   ", 0).files()).isEmpty();
        assertThat(WorkspaceTree.find(root, "env", 0).files()).isEmpty();
    }

    @Test
    void find_walks_sources_before_build_output(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("jk.toml"), "name = \"demo\"\n");
        Files.createDirectories(root.resolve("target"));
        for (int i = 0; i < 30; i++) Files.writeString(root.resolve("target/Gen" + i + ".java"), "x");
        Path deep = Files.createDirectories(root.resolve("a/b/c/d"));
        Files.writeString(deep.resolve("Gen.java"), "class Gen {}");

        WorkspaceTree.Matches m = WorkspaceTree.find(root, "gen", 5);
        assertThat(m.files().getFirst().path()).isEqualTo("a/b/c/d/Gen.java");
        assertThat(m.more()).isTrue();
    }
}
