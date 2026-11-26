// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.forge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The GitLab project and the Forgejo repository a release target derives from origin: subgroups
 * and a self-managed GitLab, Codeberg and a self-hosted Forgejo under a sub-path, the API roots,
 * and the remotes each refuses.
 */
class ForgeRepositoryTest {

    @Test
    void a_gitlab_project_keeps_its_subgroups_and_encodes_them_as_the_project_id() {
        for (String url : new String[] {
            "git@gitlab.com:acme/tools/app.git",
            "https://gitlab.com/acme/tools/app.git",
            "ssh://git@gitlab.com/acme/tools/app"
        }) {
            assertThat(ForgeRepository.parse("gitlab", url, null))
                    .as(url)
                    .isEqualTo(new GitLabProject(URI.create("https://gitlab.com"), "acme/tools/app"));
        }
        GitLabProject project = GitLabProject.parse("git@gitlab.com:acme/tools/app.git", null);
        assertThat(project.api()).hasToString("https://gitlab.com/api/v4");
        assertThat(project.project(project.api())).hasToString("https://gitlab.com/api/v4/projects/acme%2Ftools%2Fapp");
        assertThat(project.packages(project.api(), "app", "1.4.7"))
                .isEqualTo("https://gitlab.com/api/v4/projects/acme%2Ftools%2Fapp/packages/generic/app/1.4.7/");
        assertThat(project.host()).isEqualTo("gitlab.com");
        assertThat(project.kind()).isEqualTo(ForgeKind.GITLAB);
    }

    @Test
    void a_self_managed_gitlab_is_any_host_and_an_https_remote_keeps_its_port() {
        GitLabProject ssh = GitLabProject.parse("git@gitlab.acme.corp:platform/app.git", null);
        assertThat(ssh.api()).hasToString("https://gitlab.acme.corp/api/v4");
        assertThat(ssh.host()).isEqualTo("gitlab.acme.corp");

        GitLabProject port = GitLabProject.parse("http://127.0.0.1:8929/platform/app.git", null);
        assertThat(port.api()).hasToString("http://127.0.0.1:8929/api/v4");
        assertThat(port.host()).isEqualTo("127.0.0.1:8929");

        GitLabProject sshPort = GitLabProject.parse("ssh://git@gitlab.acme.corp:2222/platform/app.git", null);
        assertThat(sshPort.api()).as("an ssh port is not the web's").hasToString("https://gitlab.acme.corp/api/v4");
    }

    @Test
    void a_forgejo_repository_on_codeberg_or_a_self_hosted_sub_path() {
        ForgejoRepository codeberg = ForgejoRepository.parse("git@codeberg.org:acme/app.git", null);
        assertThat(codeberg).isEqualTo(new ForgejoRepository(URI.create("https://codeberg.org"), "acme", "app"));
        assertThat(codeberg.repos(codeberg.api())).hasToString("https://codeberg.org/api/v1/repos/acme/app");
        assertThat(codeberg.download("v1.0")).isEqualTo("https://codeberg.org/acme/app/releases/download/v1.0/");
        assertThat(codeberg.attachment("a1b2")).hasToString("https://codeberg.org/attachments/a1b2");
        assertThat(codeberg.kind()).isEqualTo(ForgeKind.GITEA);

        ForgejoRepository sub = ForgejoRepository.parse("https://git.acme.corp:3000/forge/platform/app.git", null);
        assertThat(sub.api()).hasToString("https://git.acme.corp:3000/forge/api/v1");
        assertThat(sub.repos(sub.api())).hasToString("https://git.acme.corp:3000/forge/api/v1/repos/platform/app");
        assertThat(sub.host()).isEqualTo("git.acme.corp:3000");
    }

    @Test
    void another_forges_public_host_or_a_local_path_is_refused() {
        for (String url :
                new String[] {"https://github.com/acme/app.git", "git@codeberg.org:acme/app.git", "/srv/git/app.git"}) {
            assertThatThrownBy(() -> ForgeRepository.parse("gitlab", url, null))
                    .as(url)
                    .hasMessage("origin " + url + " is not a GitLab remote");
        }
        for (String url : new String[] {
            "git@gitlab.com:acme/app.git", "https://bitbucket.org/acme/app", "https://codeberg.org/acme"
        }) {
            assertThatThrownBy(() -> ForgeRepository.parse("forgejo", url, null))
                    .as(url)
                    .hasMessage("origin " + url + " is not a Forgejo remote");
        }
    }

    @Test
    void a_missing_origin_names_the_forge(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".git"));
        Files.writeString(dir.resolve(".git/config"), "[core]\n\tbare = false\n");
        assertThatThrownBy(() -> ForgeRepository.fromOrigin("gitlab", dir, null))
                .hasMessage("release = \"gitlab\" needs a git remote origin on GitLab");
        assertThatThrownBy(() -> ForgeRepository.fromOrigin("forgejo", dir, null))
                .hasMessage("release = \"forgejo\" needs a git remote origin on Forgejo or Gitea");

        Files.writeString(
                dir.resolve(".git/config"), "[remote \"origin\"]\n\turl = git@gitlab.com:acme/tools/app.git\n");
        assertThat(ForgeRepository.fromOrigin("gitlab", dir, null))
                .isEqualTo(new GitLabProject(URI.create("https://gitlab.com"), "acme/tools/app"));
    }
}
