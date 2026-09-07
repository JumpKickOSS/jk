// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.rules;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.eval.FixtureCheck;
import cc.jumpkick.model.GuardsConfig;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Root ← packs ← members: union of rules, tighten-only below the root, every clash a load error naming both sources. */
class GuardLayersTest {

    private static final String PACK_COORD = "com.acme:house-rules:2.0.0";
    private static final GuardPacks.Coordinate PACK = Objects.requireNonNull(GuardPacks.Coordinate.parse(PACK_COORD));

    private static final String PACK_FRAGMENT = """
            [guards.no-system-out]
            kind       = "forbid"
            signatures = ["java.lang.System#out"]
            owner      = "com.acme.Log"
            instead    = "Log.info"
            why        = "stdout is not a log"
            fixture    = "guard-fixtures/no-system-out"
            [guards.no-junit4]
            kind       = "forbid"
            signatures = ["org.junit.Test"]
            locked     = true
            instead    = "org.junit.jupiter.api.Test"
            why        = "one test framework"
            """;

    /** A workspace with the pack unpacked where the loader looks, two members, and a root file that extends the pack. */
    private static Path workspace(Path dir, String rootRules, @Nullable String coreRules, @Nullable String appRules)
            throws IOException {
        Path root = Files.createDirectories(dir.resolve("ws"));
        Files.writeString(root.resolve("jk.toml"), """
                group = "t"
                name = "ws"
                version = "0.0.1"
                jdk = 25
                [workspace]
                modules = ["core", "app"]
                """);
        for (String m : List.of("core", "app")) {
            Files.createDirectories(root.resolve(m));
            Files.writeString(
                    root.resolve(m + "/jk.toml"),
                    "group = \"t\"\nname = \"" + m + "\"\nversion = \"0.0.1\"\njdk = 25\n");
        }
        if (coreRules != null) Files.writeString(root.resolve("core/jk-guards.toml"), coreRules);
        if (appRules != null) Files.writeString(root.resolve("app/jk-guards.toml"), appRules);
        Path pack = GuardPacks.unpackedDir(root, PACK);
        Files.createDirectories(pack.resolve("guard-fixtures/no-system-out"));
        Files.writeString(GuardPacks.fragment(pack), PACK_FRAGMENT);
        Files.writeString(pack.resolve("guard-fixtures/no-system-out/Bad.java"), "class Bad {}\n");
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), rootRules);
        return root;
    }

    private static final String ROOT = """
            [guards]
            extends = ["com.acme:house-rules:2.0.0"]

            [guards.one-owner]
            kind = "split-package"
            why  = "one module per package"
            """;

    private static List<String> messages(LoadResult r) {
        return r.problems().stream().map(LoadError::message).toList();
    }

    @Test
    void the_three_layers_load_as_one_set_with_each_rule_knowing_its_source(@TempDir Path dir) throws IOException {
        Path root = workspace(dir, ROOT, """
                [guards.core-no-todo]
                kind    = "text"
                pattern = "TODO"
                hit     = "TODO"
                instead = "file a ticket"
                why     = "todo rots"
                """, null);
        LoadResult r = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(r.hasErrors()).as(messages(r).toString()).isFalse();
        assertThat(r.rules().ids()).containsExactly("core-no-todo", "no-junit4", "no-system-out", "one-owner");
        Rule pack = r.rules().rule("no-system-out").orElseThrow();
        assertThat(pack.source().layer()).isEqualTo(RuleSource.Layer.PACK);
        assertThat(pack.source().render()).isEqualTo("pack com.acme:house-rules:2.0.0 jk-guards.toml:1");
        assertThat(pack.source().layerLabel()).isEqualTo("pack com.acme:house-rules:2.0.0");
        assertThat(r.rules().rule("no-junit4").orElseThrow().locked()).isTrue();
        Rule member = r.rules().rule("core-no-todo").orElseThrow();
        assertThat(member.source().layer()).isEqualTo(RuleSource.Layer.MODULE);
        assertThat(member.source().render()).isEqualTo("core/jk-guards.toml:1");
        assertThat(member.scope()).as("a member's rule defaults to the member").containsExactly("core");
        assertThat(r.rules().rule("one-owner").orElseThrow().source().layer()).isEqualTo(RuleSource.Layer.ROOT);
        assertThat(r.rules().sourceDigests().keySet())
                .containsExactlyInAnyOrder("jk-guards.toml", "pack:com.acme:house-rules:2.0.0", "core/jk-guards.toml");
        // the pack's fixture resolves beside its fragment
        Path fixture = FixtureCheck.cases(root, r.rules(), Map.of()).get(0).dir();
        assertThat(fixture).isEqualTo(GuardPacks.unpackedDir(root, PACK).resolve("guard-fixtures/no-system-out"));
    }

    @Test
    void the_root_may_amend_a_pack_rule_but_not_a_locked_one_and_never_redefine(@TempDir Path dir) throws IOException {
        Path root = workspace(dir, ROOT + """
                [guards.no-system-out]
                allow = [{ in = "tools/*", reason = "CLI tools print" }]
                [guards.no-junit4]
                allow = [{ in = "legacy/*", reason = "not yet migrated" }]
                """, null, null);
        LoadResult r = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(messages(r)).hasSize(1);
        assertThat(messages(r).get(0))
                .contains("`no-junit4` is locked by pack com.acme:house-rules:2.0.0 jk-guards.toml:8")
                .contains("cannot allow against it");
        Path redefined = workspace(dir.resolve("b"), ROOT + """
                [guards.no-system-out]
                kind = "text"
                pattern = "System.out"
                hit = "System.out"
                instead = "i"
                why = "w"
                """, null, null);
        LoadResult bad = GuardRules.load(redefined, GuardsConfig.ABSENT);
        assertThat(messages(bad))
                .singleElement()
                .asString()
                .contains("`no-system-out` is already declared by pack com.acme:house-rules:2.0.0 jk-guards.toml:1")
                .contains("never redefines an inherited one");
        // the amendment itself lands: the pack rule carries the root's allow
        Path amended = workspace(dir.resolve("c"), ROOT + """
                [guards.no-system-out]
                allow = [{ in = "tools/*", reason = "CLI tools print" }]
                """, null, null);
        LoadResult ok = GuardRules.load(amended, GuardsConfig.ABSENT);
        assertThat(ok.hasErrors()).as(messages(ok).toString()).isFalse();
        assertThat(ok.rules().rule("no-system-out").orElseThrow().allow())
                .containsExactly(new Allow("tools/*", "CLI tools print"));
    }

    @Test
    void a_member_may_only_tighten(@TempDir Path dir) throws IOException {
        Path root = workspace(dir, ROOT, """
                [guards.one-owner]
                kind = "split-package"
                why  = "redefined"
                [guards.core-allow]
                kind    = "text"
                pattern = "x"
                hit     = "x"
                allow   = [{ in = "core/*", reason = "r" }]
                instead = "i"
                why     = "w"
                [guards.core-wide]
                kind    = "text"
                pattern = "y"
                hit     = "y"
                scope   = ["app"]
                instead = "i"
                why     = "w"
                [guards.core-locked]
                kind    = "text"
                pattern = "z"
                hit     = "z"
                locked  = true
                instead = "i"
                why     = "w"
                [guards.core-inside]
                kind    = "text"
                pattern = "q"
                hit     = "q"
                scope   = ["core/src/main"]
                instead = "i"
                why     = "w"
                """, null);
        LoadResult r = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(messages(r))
                .anySatisfy(m -> assertThat(m).contains("`one-owner` is already declared by jk-guards.toml:4"))
                .anySatisfy(m -> assertThat(m).contains("a member's rule carries no `allow`"))
                .anySatisfy(m -> assertThat(m).contains("scope `app` reaches outside core"))
                .anySatisfy(m -> assertThat(m).contains("`locked` is a pack's word"));
        assertThat(messages(r)).hasSize(4);
        assertThat(r.rules().rule("core-inside").orElseThrow().scope()).containsExactly("core/src/main");
    }

    @Test
    void the_load_does_not_depend_on_discovery_order(@TempDir Path dir) throws IOException {
        String core = "[guards.core-a]\nkind = \"text\"\npattern = \"a\"\nhit = \"a\"\ninstead = \"i\"\nwhy = \"w\"\n";
        String app = "[guards.app-b]\nkind = \"text\"\npattern = \"b\"\nhit = \"b\"\ninstead = \"i\"\nwhy = \"w\"\n";
        Path root = workspace(dir, ROOT, core, app);
        LoadResult first = GuardRules.load(root, GuardsConfig.ABSENT);
        Files.writeString(
                root.resolve("jk.toml"),
                Files.readString(root.resolve("jk.toml"))
                        .replace("modules = [\"core\", \"app\"]", "modules = [\"app\", \"core\"]"));
        LoadResult shuffled = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(shuffled.hasErrors()).as(messages(shuffled).toString()).isFalse();
        assertThat(shuffled.rules().ids()).isEqualTo(first.rules().ids());
        assertThat(shuffled.rules().sourceDigests()).isEqualTo(first.rules().sourceDigests());
        for (String id : first.rules().ids()) {
            assertThat(shuffled.rules().rule(id).orElseThrow().source().render())
                    .isEqualTo(first.rules().rule(id).orElseThrow().source().render());
        }
    }

    @Test
    void a_pack_that_is_declared_but_not_unpacked_is_a_load_error_naming_the_fix(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectories(dir.resolve("ws"));
        Files.writeString(root.resolve("jk.toml"), "group = \"t\"\nname = \"ws\"\nversion = \"0.0.1\"\njdk = 25\n");
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), """
                [guards]
                extends = ["com.acme:house-rules:2.0.0", "floating:pack:2"]
                """);
        LoadResult r = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(messages(r))
                .anySatisfy(m -> assertThat(m)
                        .contains("pack com.acme:house-rules:2.0.0 is not unpacked")
                        .contains("run `jk lock`"))
                .anySatisfy(m -> assertThat(m).contains("pack floating:pack:2 is not unpacked"))
                .hasSize(2); // one message per pack, none silently skipped
        // pack
    }

    @Test
    void a_pack_jar_unpacks_its_fragment_and_fixtures_once_per_sha(@TempDir Path dir) throws IOException {
        Path jar = dir.resolve("house-rules-2.0.0.jar");
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream jos = new JarOutputStream(out)) {
            entry(jos, "jk-guards.toml", PACK_FRAGMENT);
            entry(jos, "guard-fixtures/no-system-out/Bad.java", "class Bad {}\n");
            entry(jos, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n");
            entry(jos, "com/acme/Extra.class", "");
        }
        Path unpacked = dir.resolve("unpacked");
        GuardPacks.unpack(jar, unpacked, "abc");
        assertThat(GuardPacks.fragment(unpacked)).hasContent(PACK_FRAGMENT);
        assertThat(unpacked.resolve("guard-fixtures/no-system-out/Bad.java")).exists();
        assertThat(unpacked.resolve("com")).doesNotExist();
        Files.writeString(GuardPacks.fragment(unpacked), "tampered");
        GuardPacks.unpack(jar, unpacked, "abc");
        assertThat(GuardPacks.fragment(unpacked))
                .as("same sha: a stat, not a re-read")
                .hasContent("tampered");
        GuardPacks.unpack(jar, unpacked, "def");
        assertThat(GuardPacks.fragment(unpacked)).as("new sha: unpacked again").hasContent(PACK_FRAGMENT);
        assertThat(GuardPacks.declared("[guards]\nextends = [\"a:b:1\", \"c:d:2\"]\n"))
                .containsExactly("a:b:1", "c:d:2");
        assertThat(GuardPacks.Coordinate.parse("a:b")).isNull();
    }

    private static void entry(JarOutputStream jos, String name, String content) throws IOException {
        jos.putNextEntry(new JarEntry(name));
        jos.write(content.getBytes(StandardCharsets.UTF_8));
        jos.closeEntry();
    }
}
