// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.host.Os;
import cc.jumpkick.jdk.JdkEnsure;
import cc.jumpkick.jdk.JdkRegistry;
import cc.jumpkick.jdk.JdkResolution;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.protocol.ProjectInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The pre-flight against engine summaries handed in directly: what it asks for, in which order, and
 * how it words the install. The summaries' values are the engine's normalized ones — a resolver
 * spec and an effective {@code java} level, per module — exactly as {@code project-info} answers
 * them; a workspace of any width costs the root's summary and no more.
 */
class JdkPreflightTest {

    @BeforeEach
    @AfterEach
    void forgetRegistries() {
        JdkEnsure.resetSharedRegistries();
    }

    /** The summaries an engine would give, keyed by directory, remembering who was asked. */
    private static final class Summaries {
        final Map<Path, ProjectInfo> byDir = new HashMap<>();
        final List<Path> asked = new ArrayList<>();

        @Nullable
        ProjectInfo of(Path dir) {
            asked.add(dir.toAbsolutePath().normalize());
            return byDir.get(dir.toAbsolutePath().normalize());
        }

        /** The summary the caller already holds for the entry directory, as a verb would. */
        ProjectInfo given(Path dir) {
            return Objects.requireNonNull(of(dir), "summary for " + dir);
        }
    }

    @Test
    void a_member_naming_its_own_pin_is_pre_flighted_with_the_engine_s_spec(@TempDir Path tmp) throws IOException {
        Path root = workspace(tmp, "exact");
        Path exact = member(root, "exact", "jdk = \"=temurin-21\"\njava = 21\n");
        Path jdks = jdksWith(tmp.resolve("jdks"), "temurin-25.0.2");
        Summaries summaries = new Summaries();
        summaries.byDir.put(root, info("", 25, root, true, Map.of(exact, "temurin-21@21")));

        List<JdkPreflight.Need> needs = JdkPreflight.needs(root, summaries.given(root), jdks, summaries::of);

        assertThat(summaries.asked)
                .as("the member's pin rides the root's summary")
                .containsExactly(root);
        assertThat(needs).hasSize(1);
        JdkPreflight.Need need = needs.getFirst();
        assertThat(need.dir()).isEqualTo(exact);
        assertThat(need.jdkSpec()).isEqualTo("temurin-21");
        assertThat(need.javaRelease()).isEqualTo(21);
        assertThat(need.pending()).isEqualTo(new JdkEnsure.Pending("temurin-21", JdkResolution.Tier.PROJECT_TOML));
        // The spec it will install under is one the registry's matcher finds again afterwards.
        Path later = jdksWith(tmp.resolve("later"), "temurin-21.0.5");
        assertThat(new JdkRegistry(later).findBySpec(need.pending().spec())).isPresent();
    }

    @Test
    void a_member_that_inherits_the_workspace_toolchain_is_not_asked_for_a_summary(@TempDir Path tmp)
            throws IOException {
        Path root = workspace(tmp, "lib");
        Path lib = member(root, "lib", "");
        Path jdks = jdksWith(tmp.resolve("jdks"), "temurin-25.0.2");
        Summaries summaries = new Summaries();
        summaries.byDir.put(root, info("temurin-25", 25, root, true, Map.of(lib, "temurin-25@25")));

        List<JdkPreflight.Need> needs = JdkPreflight.needs(root, summaries.given(root), jdks, summaries::of);

        assertThat(needs).isEmpty();
        assertThat(summaries.asked).containsExactly(root);
    }

    @Test
    void a_member_that_declares_only_a_java_level_is_pre_flighted_off_the_root_s_summary(@TempDir Path tmp)
            throws IOException {
        Path root = workspace(tmp, "old");
        Path old = member(root, "old", "java = 17\n");
        Path jdks = jdksWith(tmp.resolve("jdks"), "temurin-25.0.2");
        Summaries summaries = new Summaries();
        summaries.byDir.put(root, info("temurin-25", 25, root, true, Map.of(old, "temurin-17@17")));

        List<JdkPreflight.Need> needs = JdkPreflight.needs(root, summaries.given(root), jdks, summaries::of);

        assertThat(summaries.asked).containsExactly(root);
        assertThat(needs).extracting(n -> n.pending().spec()).containsExactly("temurin-17");
        assertThat(needs.getFirst().javaRelease()).isEqualTo(17);
    }

    @Test
    void a_workspace_with_several_pinned_members_costs_one_summary(@TempDir Path tmp) throws IOException {
        Path root = workspace(tmp, "a", "b", "c", "d");
        Path a = member(root, "a", "jdk = \"=temurin-17\"\njava = 17\n");
        Path b = member(root, "b", "jdk = \"zulu\"\njava = 21\n");
        Path c = member(root, "c", "java = 25\n");
        Path d = member(root, "d", "");
        Path jdks = jdksWith(tmp.resolve("jdks"), "temurin-25.0.2");
        Map<Path, String> pins = new LinkedHashMap<>();
        pins.put(a, "temurin-17@17");
        pins.put(b, "zulu-21@21");
        pins.put(c, "temurin-25@25");
        pins.put(d, "temurin-25@25");
        Summaries summaries = new Summaries();
        summaries.byDir.put(root, info("temurin-25", 25, root, true, pins));

        List<JdkPreflight.Need> needs = JdkPreflight.needs(root, summaries.given(root), jdks, summaries::of);

        assertThat(summaries.asked)
                .as("one project-info request for the whole workspace")
                .containsExactly(root);
        assertThat(needs).extracting(JdkPreflight.Need::dir).containsExactly(a, b);
        assertThat(needs).extracting(n -> n.pending().spec()).containsExactly("temurin-17", "zulu-21");
    }

    @Test
    void a_member_inheriting_by_inline_table_resolves_like_the_root(@TempDir Path tmp) throws IOException {
        Path root = workspace(tmp, "inline");
        Path inline = member(root, "inline", "jdk = { workspace = true }\n");
        Path jdks = jdksWith(tmp.resolve("jdks"), "temurin-25.0.2");
        Summaries summaries = new Summaries();
        summaries.byDir.put(root, info("temurin-25", 25, root, true, Map.of(inline, "temurin-25@25")));

        List<JdkPreflight.Need> needs = JdkPreflight.needs(root, summaries.given(root), jdks, summaries::of);

        assertThat(needs).isEmpty();
    }

    @Test
    void from_a_member_directory_the_workspace_root_is_pre_flighted_first(@TempDir Path tmp) throws IOException {
        Path root = workspace(tmp, "exact");
        Path exact = member(root, "exact", "jdk = \"=temurin-21\"\njava = 21\n");
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Summaries summaries = new Summaries();
        summaries.byDir.put(root, info("zulu-25", 25, root, true, Map.of(exact, "temurin-21@21")));
        summaries.byDir.put(exact, info("temurin-21", 21, root, false, Map.of(exact, "temurin-21@21")));

        List<JdkPreflight.Need> needs = JdkPreflight.needs(exact, summaries.given(exact), jdks, summaries::of);

        assertThat(summaries.asked)
                .as("the entry's summary is held; the root's is the one request")
                .containsExactly(exact, root);
        assertThat(needs).extracting(JdkPreflight.Need::dir).containsExactly(root, exact);
        assertThat(needs).extracting(n -> n.pending().spec()).containsExactly("zulu-25", "temurin-21");
    }

    @Test
    void a_malformed_lock_is_left_to_the_engine(@TempDir Path tmp) throws IOException {
        Path root = workspace(tmp, "exact");
        member(root, "exact", "jdk = \"=temurin-21\"\njava = 21\n");
        Files.writeString(root.resolve("jk-lock.toml"), "version = \"one\"\n[jdk\n");
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Summaries summaries = new Summaries();
        summaries.byDir.put(root, info("temurin-21", 21, root, true, Map.of()));

        List<JdkPreflight.Need> needs = JdkPreflight.needs(root, summaries.given(root), jdks, summaries::of);

        assertThat(needs).hasSize(1);
        assertThat(needs.getFirst().lockJdk()).isNull();
        assertThat(needs.getFirst().pending().spec()).isEqualTo("temurin-21");
    }

    @Test
    void without_an_engine_summary_the_pre_flight_stands_down(@TempDir Path tmp) {
        boolean ok = JdkPreflight.ensure(tmp, null, tmp.resolve("jdks"), BuildPlanConsole.Mode.QUIET, dir -> {
            throw new AssertionError("no summary should be asked for " + dir);
        });

        assertThat(ok).isTrue();
    }

    @Test
    void the_header_names_what_asked_for_the_jdk(@TempDir Path tmp) {
        Path app = tmp.resolve("app");

        assertThat(JdkPreflight.header(app, 21, pending("temurin-21", JdkResolution.Tier.PROJECT_TOML)))
                .isEqualTo("app/jk.toml pins JDK temurin-21 — installing it");
        assertThat(JdkPreflight.header(app, 21, pending("zulu-21", JdkResolution.Tier.JDK_VERSION_FILE)))
                .isEqualTo("app/.jdk-version pins JDK zulu-21 — installing it");
        assertThat(JdkPreflight.header(app, 21, pending("temurin-21", JdkResolution.Tier.LOCKFILE)))
                .isEqualTo("app/jk-lock.toml pins JDK temurin-21 — installing it");
        assertThat(JdkPreflight.header(app, 26, pending(">=26", JdkResolution.Tier.JAVA_RELEASE_FLOOR)))
                .isEqualTo("app/jk.toml sets java = 26, which needs a JDK >=26 — installing one");
        assertThat(JdkPreflight.header(app, 0, pending("temurin-25", JdkResolution.Tier.DEFAULT)))
                .isEqualTo("no JDK is installed — installing temurin-25");
        assertThat(JdkPreflight.header(app, 0, pending("zulu-21", JdkResolution.Tier.SWITCH)))
                .isEqualTo("JDK zulu-21 is selected for this run (--jdk / JK_JDK) — installing it");
    }

    private static JdkEnsure.Pending pending(String spec, JdkResolution.Tier tier) {
        return new JdkEnsure.Pending(spec, tier);
    }

    /** A workspace root at {@code tmp/ws} listing {@code modules}; the root pins nothing itself. */
    static Path workspace(Path tmp, String... modules) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("ws"));
        StringBuilder list = new StringBuilder();
        for (String m : modules)
            list.append(list.isEmpty() ? "" : ", ").append('"').append(m).append('"');
        Files.writeString(root.resolve("jk.toml"), """
                name = "ws"
                group = "example"
                version = "0.1.0"
                java = 25

                [workspace]
                modules = [%s]
                """.formatted(list));
        return root;
    }

    /** A member whose manifest carries {@code extra} beside the identity keys. */
    static Path member(Path root, String name, String extra) throws IOException {
        Path dir = Files.createDirectories(root.resolve(name));
        Files.writeString(
                dir.resolve("jk.toml"), "name = \"" + name + "\"\ngroup = \"example\"\nversion = \"0.1.0\"\n" + extra);
        return dir;
    }

    /** A JDK root holding fake installs the registry's probe accepts (a javac, a release file). */
    static Path jdksWith(Path jdks, String... dirNames) throws IOException {
        Files.createDirectories(jdks);
        for (String dirName : dirNames) {
            Path home = jdks.resolve(dirName);
            Files.createDirectories(home.resolve("bin"));
            Files.writeString(home.resolve("bin/java"), "#!/fake");
            Files.writeString(home.resolve("bin/javac"), "#!/fake");
            if (Os.isWindows()) {
                Files.writeString(home.resolve("bin/java.exe"), "fake");
                Files.writeString(home.resolve("bin/javac.exe"), "fake");
            }
            String version = dirName.substring(dirName.indexOf('-') + 1);
            Files.writeString(
                    home.resolve("release"), "JAVA_VERSION=\"" + version + "\"\nIMPLEMENTOR=\"Eclipse Adoptium\"\n");
        }
        return jdks;
    }

    /**
     * An engine summary with the toolchain fields the pre-flight reads, as {@code project-info}
     * encodes them: the dir's own spec and level, and every member's as {@code <spec>@<java>}.
     */
    static ProjectInfo info(String jdk, int javaRelease, Path root, boolean workspaceRoot, Map<Path, String> members) {
        StringBuilder mods = new StringBuilder();
        StringBuilder toolchains = new StringBuilder();
        for (var m : members.entrySet()) {
            String dir = m.getKey().toAbsolutePath().normalize().toString();
            if (!mods.isEmpty()) mods.append(',');
            mods.append(Jsonl.quote(dir))
                    .append(':')
                    .append(Jsonl.quote(m.getKey().getFileName().toString()));
            if (!toolchains.isEmpty()) toolchains.append(',');
            toolchains.append(Jsonl.quote(dir)).append(':').append(Jsonl.quote(m.getValue()));
        }
        return ProjectInfo.decode("{\"type\":\"project-info-ack\",\"name\":\"x\",\"jdk\":"
                + Jsonl.quote(jdk)
                + ",\"javaRelease\":" + javaRelease
                + ",\"workspaceRoot\":" + workspaceRoot
                + ",\"workspaceRootDir\":"
                + Jsonl.quote(root.toAbsolutePath().normalize().toString())
                + ",\"modules\":{" + mods + "},\"toolchains\":{" + toolchains + "}}");
    }
}
