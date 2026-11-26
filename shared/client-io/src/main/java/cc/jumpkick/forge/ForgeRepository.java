// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.forge;

import java.nio.file.Path;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The repository a forge release target ({@code [publish] release = "github"}, {@code "gitlab"} or
 * {@code "forgejo"}) writes to, as the git remote {@code origin} names it. The configured value
 * names the forge, so a self-hosted host is taken as that forge unless it is another forge's
 * public one.
 */
public sealed interface ForgeRepository permits GitHubRepository, GitLabProject, ForgejoRepository {

    /** The forge kind whose token chain authenticates the release. */
    ForgeKind kind();

    /** The host the token chain looks a stored token up for. */
    String host();

    /** How a message names the forge: {@code GitHub}, {@code GitLab} or {@code Forgejo}. */
    String forge();

    /** What a user does about a missing or refused token, e.g. {@code run jk auth login gitlab or set GITLAB_TOKEN}. */
    String login();

    /**
     * The repository of {@code release}'s forge that {@code dir}'s checkout names as {@code origin},
     * an {@code ~/.ssh/config} host alias resolved.
     *
     * @throws IllegalArgumentException with one line when origin is missing or not on that forge
     */
    static ForgeRepository fromOrigin(String release, Path dir) {
        Path sshConfig = Optional.ofNullable(System.getProperty("user.home"))
                .map(h -> Path.of(h, ".ssh", "config"))
                .orElse(null);
        return fromOrigin(release, dir, sshConfig);
    }

    /** As {@link #fromOrigin(String, Path)} with the ssh config given. */
    static ForgeRepository fromOrigin(String release, Path dir, @Nullable Path sshConfig) {
        String url = GitForgeDetector.readOriginUrl(dir)
                .orElseThrow(() -> new IllegalArgumentException(
                        "release = \"" + release + "\" needs a git remote origin on " + forgeName(release)));
        return parse(release, url, sshConfig);
    }

    /**
     * {@code remoteUrl} as a repository on {@code release}'s forge.
     *
     * @throws IllegalArgumentException with one line when it names none
     */
    static ForgeRepository parse(String release, String remoteUrl, @Nullable Path sshConfig) {
        return switch (release) {
            case "github" -> GitHubRepository.parse(remoteUrl, sshConfig);
            case "gitlab" -> GitLabProject.parse(remoteUrl, sshConfig);
            case "forgejo" -> ForgejoRepository.parse(remoteUrl, sshConfig);
            default -> throw new IllegalArgumentException("`" + release + "` is not github, gitlab or forgejo");
        };
    }

    private static String forgeName(String release) {
        return switch (release) {
            case "gitlab" -> "GitLab";
            case "forgejo" -> "Forgejo or Gitea";
            default -> "GitHub";
        };
    }
}
