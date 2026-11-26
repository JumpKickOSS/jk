// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.forge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The GitHub repository a project's origin names, and the API and upload roots of its host. */
class GitHubRepositoryTest {

    @Test
    void every_remote_shape_names_the_owner_and_repository() {
        for (String url : new String[] {
            "https://github.com/acme/app.git",
            "https://github.com/acme/app",
            "git@github.com:acme/app.git",
            "ssh://git@github.com/acme/app.git"
        }) {
            assertThat(GitHubRepository.parse(url, null))
                    .as(url)
                    .isEqualTo(new GitHubRepository("github.com", "acme", "app"));
        }
    }

    @Test
    void github_dot_com_has_its_own_api_and_upload_hosts() {
        GitHubRepository repo = new GitHubRepository("github.com", "acme", "app");
        assertThat(repo.api()).isEqualTo(URI.create("https://api.github.com"));
        assertThat(repo.uploads()).isEqualTo(URI.create("https://uploads.github.com"));
        assertThat(repo.repos(repo.api())).isEqualTo(URI.create("https://api.github.com/repos/acme/app"));
        assertThat(repo.download("v1.4.7")).isEqualTo("https://github.com/acme/app/releases/download/v1.4.7/");
    }

    @Test
    void an_enterprise_server_serves_both_under_its_own_host() {
        GitHubRepository repo = GitHubRepository.parse("git@git.acme.corp:platform/app.git", null);
        assertThat(repo.host()).isEqualTo("git.acme.corp");
        assertThat(repo.api()).isEqualTo(URI.create("https://git.acme.corp/api/v3"));
        assertThat(repo.uploads()).isEqualTo(URI.create("https://git.acme.corp/api/uploads"));
        assertThat(repo.repos(repo.api())).isEqualTo(URI.create("https://git.acme.corp/api/v3/repos/platform/app"));
    }

    @Test
    void an_enterprise_cloud_host_has_api_and_upload_subdomains() {
        GitHubRepository repo = GitHubRepository.parse("https://acme.ghe.com/platform/app", null);
        assertThat(repo.api()).isEqualTo(URI.create("https://api.acme.ghe.com"));
        assertThat(repo.uploads()).isEqualTo(URI.create("https://uploads.acme.ghe.com"));
    }

    @Test
    void an_ssh_alias_resolves_to_its_real_host(@TempDir Path dir) throws Exception {
        Path ssh = Files.writeString(dir.resolve("config"), "Host work-gh\n  HostName github.com\n");
        assertThat(GitHubRepository.parse("git@work-gh:acme/app.git", ssh))
                .isEqualTo(new GitHubRepository("github.com", "acme", "app"));
    }

    @Test
    void another_forge_or_a_local_path_is_not_a_github_remote() {
        for (String url : new String[] {
            "https://gitlab.com/acme/app.git",
            "git@codeberg.org:acme/app.git",
            "/srv/git/app.git",
            "file:///srv/git/app"
        }) {
            assertThatThrownBy(() -> GitHubRepository.parse(url, null))
                    .as(url)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("origin " + url + " is not a GitHub remote");
        }
        assertThatThrownBy(() -> GitHubRepository.parse("https://github.com/acme", null))
                .hasMessage("origin https://github.com/acme is not a GitHub remote");
    }

    @Test
    void the_origin_is_read_from_the_checkout(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".git"));
        Files.writeString(dir.resolve(".git/config"), "[remote \"origin\"]\n\turl = git@github.com:acme/app.git\n");
        assertThat(ForgeRepository.fromOrigin("github", dir.resolve("."), null))
                .isEqualTo(new GitHubRepository("github.com", "acme", "app"));

        Path other = dir.resolve("other");
        Files.createDirectories(other.resolve(".git"));
        Files.writeString(other.resolve(".git/config"), "[core]\n\tbare = false\n");
        assertThatThrownBy(() -> ForgeRepository.fromOrigin("github", other, null))
                .hasMessage("release = \"github\" needs a git remote origin on GitHub");
    }
}
