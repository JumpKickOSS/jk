// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.lock.RepoSource;
import cc.jumpkick.model.RepositorySpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A repository declared in the machine-local {@code ~/.config/jk/config.toml} reaches the lockfile:
 * {@code LockOrchestrator} writes each artifact's {@code source} as {@code <name>+<baseUrl>}, and
 * {@code jk-lock.toml} is committed. So whatever that base URL carries is committed with it.
 *
 * <p>Credentials in a URL are the case that matters. {@code https://alice:token@nexus.example.com/}
 * authenticates nothing — the JDK's {@code HttpClient} does not read userinfo, and jk's own auth
 * runs through {@link RepoCredentialResolver} and an {@code Authorization} header — so the
 * credential half of such a URL is inert on the wire and live in every place the URL is recorded.
 *
 * <p>These cases walk the whole chain that a build walks: the user config file, the parsed {@link
 * RepositorySpec}, the {@link MavenRepo} built from it, the exact {@code source} expression the
 * resolver interpolates, and the rendered {@code jk-lock.toml} text.
 */
class UserConfigRepoLockSourceTest {

    private static final String USER = "alice";
    private static final String TOKEN = "s3cr3t-nexus-token";

    @AfterEach
    void clearOverride() {
        System.clearProperty("jk.env.JK_CONFIG_FILE");
    }

    /** {@code JkDirs} reads every environment variable through a system-property seam. */
    private static List<RepositorySpec> userConfigRepositories(Path configFile, String url) throws Exception {
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, """
            [repositories.internal]
            url = "%s"
            groups = ["com.acme"]
            """.formatted(url));
        System.setProperty("jk.env.JK_CONFIG_FILE", configFile.toString());
        return GlobalConfig.repositories();
    }

    @Test
    void a_credential_in_a_user_declared_repo_url_cannot_reach_the_lockfile(@TempDir Path tmp) throws Exception {
        List<RepositorySpec> declared = userConfigRepositories(
                tmp.resolve("config/jk/config.toml"), "https://" + USER + ":" + TOKEN + "@nexus.example.com/repo");

        assertThat(declared).hasSize(1);
        assertThat(declared.get(0).url().getUserInfo())
                .as("the parse is faithful — the config file really does declare a credential")
                .isEqualTo(USER + ":" + TOKEN);

        MavenRepo repo =
                new MavenRepo(declared.get(0).name(), declared.get(0).url(), new Http(), new Cas(tmp.resolve("store")));

        // The exact expression LockOrchestrator interpolates into Artifact.source.
        String source = repo.name() + "+" + repo.baseUrl();
        assertThat(source).isEqualTo("internal+https://nexus.example.com/repo/");
        assertThat(source).doesNotContain(TOKEN).doesNotContain(USER).doesNotContain("@");

        // …and through the writer, since the lockfile is the artifact that gets committed.
        Path lock = tmp.resolve("jk-lock.toml");
        LockfileWriter.write(lockfileWith(source), lock);
        String rendered = Files.readString(lock);
        assertThat(rendered).contains("source   = \"" + source + "\"");
        assertThat(rendered).doesNotContain(TOKEN);

        // The round trip a consumer makes: source → repo URL.
        assertThat(RepoSource.parse(source).url()).isEqualTo("https://nexus.example.com/repo/");
        assertThat(RepoSource.parse(source).name()).isEqualTo("internal");
    }

    /** Same for a bare token as the whole userinfo, which is how forge registries are usually pasted. */
    @Test
    void a_bare_token_userinfo_is_stripped_too(@TempDir Path tmp) throws Exception {
        List<RepositorySpec> declared = userConfigRepositories(
                tmp.resolve("config/jk/config.toml"), "https://ghp_" + TOKEN + "@maven.pkg.github.com/acme");

        MavenRepo repo =
                new MavenRepo(declared.get(0).name(), declared.get(0).url(), new Http(), new Cas(tmp.resolve("store")));

        assertThat(repo.baseUrl().toString()).isEqualTo("https://maven.pkg.github.com/acme/");
        assertThat(repo.baseUrl().toString()).doesNotContain(TOKEN);
    }

    /** Stripping is surgical: a credential-free user repo reaches the lockfile exactly as declared. */
    @Test
    void a_credential_free_user_repo_is_recorded_verbatim(@TempDir Path tmp) throws Exception {
        List<RepositorySpec> declared =
                userConfigRepositories(tmp.resolve("config/jk/config.toml"), "https://nexus.example.com/repo/");

        MavenRepo repo =
                new MavenRepo(declared.get(0).name(), declared.get(0).url(), new Http(), new Cas(tmp.resolve("store")));

        assertThat(repo.name() + "+" + repo.baseUrl()).isEqualTo("internal+https://nexus.example.com/repo/");
    }

    private static Lockfile lockfileWith(String source) {
        return new Lockfile(
                1,
                "jk-test",
                "pubgrub",
                List.of(new Lockfile.Artifact(
                        "com.acme:lib:jar:",
                        "1.0.0",
                        source,
                        "sha256:" + "0".repeat(64),
                        "com/acme/lib/1.0.0/lib-1.0.0.jar",
                        List.of())));
    }
}
