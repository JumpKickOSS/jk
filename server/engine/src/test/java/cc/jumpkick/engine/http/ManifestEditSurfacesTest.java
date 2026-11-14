// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.tools.DepsTool;
import cc.jumpkick.engine.verbs.EditVerb;
import cc.jumpkick.engine.verbs.ManifestEdit;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.runtime.LockFlow;
import cc.jumpkick.runtime.RepoFixtures;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One manifest edit on every surface: {@code jk add}'s wire op, {@code POST /api/project/deps} and
 * MCP {@code deps} with the same coordinate write the same {@code jk.toml}, byte for byte, relock,
 * and refuse an add the deny policy bans the same way, putting both files back.
 */
@Tag("integration")
@SuppressWarnings("NullAway")
class ManifestEditSurfacesTest extends HttpEngineServerHarness {

    @TempDir
    Path tmp;

    @Test
    void the_same_add_writes_the_same_manifest_and_result_on_every_surface() throws Exception {
        Path repo = repo(false);
        Path cli = project(repo, "cli", false);
        Path rest = project(repo, "rest", false);
        Path mcp = project(repo, "mcp", false);

        ManifestEdit.Result viaCli = cliAdd(cli);
        HttpResponse<String> viaRest = restAdd(rest);
        Map<String, Object> viaMcp = mcpAdd(mcp);

        assertThat(viaCli.error()).isNull();
        assertThat(viaCli.lock()).as(String.valueOf(viaCli)).isEqualTo(ManifestEdit.LOCK_OK);
        assertThat(viaRest.statusCode()).as(viaRest.body()).isEqualTo(200);
        assertThat(MiniJson.parse(viaRest.body()))
                .isEqualTo(MiniJson.parse(MiniJson.write(ManifestEdit.structured(viaCli))));
        assertThat(text(viaMcp)).isEqualTo("add com.acme:thing:1.0.0\nlock ok\n");
        byte[] written = Files.readAllBytes(cli.resolve("jk.toml"));
        assertThat(new String(written)).contains("thing = \"com.acme:thing:1.0.0\"");
        assertThat(Files.readAllBytes(rest.resolve("jk.toml"))).isEqualTo(written);
        assertThat(Files.readAllBytes(mcp.resolve("jk.toml"))).isEqualTo(written);
        for (Path p : List.of(cli, rest, mcp)) {
            assertThat(Files.readString(p.resolve("jk-lock.toml"))).contains("com.acme");
        }
    }

    @Test
    void a_deny_policy_hit_is_refused_identically_and_writes_nothing() throws Exception {
        Path repo = repo(true);
        Path cli = project(repo, "cli", true);
        Path rest = project(repo, "rest", true);
        Path mcp = project(repo, "mcp", true);
        byte[] manifest = Files.readAllBytes(cli.resolve("jk.toml"));
        Map<Path, byte[]> locks = new HashMap<>();
        for (Path p : List.of(cli, rest, mcp)) locks.put(p, Files.readAllBytes(p.resolve("jk-lock.toml")));

        ManifestEdit.Result viaCli = cliAdd(cli);
        HttpResponse<String> viaRest = restAdd(rest);
        Map<String, Object> viaMcp = mcpAdd(mcp);

        assertThat(viaCli.error())
                .startsWith("deny policy refuses the edit; nothing written:")
                .contains("[deny.licenses] allow");
        assertThat(viaRest.statusCode()).isEqualTo(422);
        assertThat(MiniJson.str(MiniJson.parse(viaRest.body()), "error")).isEqualTo(viaCli.error());
        assertThat(viaMcp.get("isError")).isEqualTo(true);
        assertThat(text(viaMcp)).isEqualTo(viaCli.error() + "\n");
        for (Path p : List.of(cli, rest, mcp)) {
            assertThat(Files.readAllBytes(p.resolve("jk.toml"))).isEqualTo(manifest);
            assertThat(Files.readAllBytes(p.resolve("jk-lock.toml"))).isEqualTo(locks.get(p));
        }
    }

    /** What {@code jk add com.acme:thing:1.0.0} sends, through the verb's own decoder. */
    private static ManifestEdit.Result cliAdd(Path dir) {
        ManifestEdit.Op op = EditVerb.op("add-dependency", List.of("main", "thing", "com.acme", "thing", "1.0.0", ""));
        return ManifestEdit.run(new ManifestEdit.Request(dir.resolve("jk.toml"), List.of(op), false, true));
    }

    private HttpResponse<String> restAdd(Path dir) throws Exception {
        return postJson(
                "/api/project/deps",
                "{\"dir\":" + Jsonl.quote(dir.toString())
                        + ",\"action\":\"add\",\"coords\":[\"com.acme:thing:1.0.0\"]}");
    }

    private static Map<String, Object> mcpAdd(Path dir) {
        return new DepsTool()
                .call(new McpCall(
                        null,
                        Map.of("action", "add", "coords", List.of("com.acme:thing:1.0.0"), "dir", dir.toString()),
                        null));
    }

    private Path repo(boolean gpl) throws Exception {
        Path repo = tmp.resolve("repo");
        RepoFixtures.module(repo, "com.acme", "thing", "1.0.0");
        Files.write(repo.resolve("com/acme/thing/1.0.0/thing-1.0.0.jar"), new byte[] {'P', 'K', 5, 6});
        if (gpl) {
            Files.writeString(repo.resolve("com/acme/thing/1.0.0/thing-1.0.0.pom"), """
                    <project>
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>com.acme</groupId>
                      <artifactId>thing</artifactId>
                      <version>1.0.0</version>
                      <licenses><license><name>GPL-3.0</name></license></licenses>
                    </project>
                    """);
        }
        return repo;
    }

    /** A locked project whose only repository is {@code repo}; {@code deny} allows Apache-2.0 alone. */
    private Path project(Path repo, String name, boolean deny) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve(name));
        Files.writeString(
                dir.resolve("jk.toml"),
                """
                group = "com.example"
                name = "app"
                version = "0.1.0"

                [repositories.local]
                url = "%s"
                groups = ["com.acme"]
                """.formatted(repo.toUri()) + (deny ? "\n[deny.licenses]\nallow = [\"Apache-2.0\"]\n" : ""));
        LockFlow.Result first = LockFlow.run(dir, dir.resolve("cache"), List.of(), false, null);
        assertThat(first.status()).as("the first lock: %s", first.error()).isZero();
        return dir;
    }

    private static String text(Map<String, Object> result) {
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>)
                Objects.requireNonNull((List<?>) result.get("content")).getFirst();
        return String.valueOf(row.get("text"));
    }
}
