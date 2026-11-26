// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Whether a repository's CI configuration runs {@code jk publish}: the usual files of GitHub
 * Actions, GitLab, Forgejo, Gitea, Woodpecker, CircleCI, Buildkite, Jenkins, Azure Pipelines,
 * Bitbucket and Drone. A pipeline that only tests does not count.
 */
final class CiPublishScan {

    private CiPublishScan() {}

    private static final List<String> FILES = List.of(
            ".gitlab-ci.yml",
            ".woodpecker.yml",
            ".circleci/config.yml",
            "Jenkinsfile",
            "azure-pipelines.yml",
            "bitbucket-pipelines.yml",
            ".drone.yml");

    private static final List<String> DIRECTORIES =
            List.of(".github/workflows", ".forgejo/workflows", ".gitea/workflows", ".woodpecker", ".buildkite");

    private static final Pattern PUBLISH_LINE =
            Pattern.compile("(?:^|[\\s;&|(\"'`:-])jk(?:\\s+-\\S+)*\\s+publish(?:\\s|$|[;&|)\"'`])");

    /** True when a CI file under {@code root} has a line that runs {@code jk publish}. */
    static boolean runsPublish(Path root) {
        List<Path> files = new ArrayList<>();
        for (String name : FILES) files.add(root.resolve(name));
        for (String directory : DIRECTORIES) {
            Path dir = root.resolve(directory);
            if (!Files.isDirectory(dir)) continue;
            try {
                files.addAll(PathUtil.childFiles(dir));
            } catch (IOException ignored) {
                // an unreadable directory is no evidence of a publish
            }
        }
        for (Path file : files) {
            if (Files.isRegularFile(file) && mentionsPublish(file)) return true;
        }
        return false;
    }

    private static boolean mentionsPublish(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
        for (String raw : lines) {
            String line = raw.strip();
            if (line.startsWith("#") || line.startsWith("//")) continue;
            if (PUBLISH_LINE.matcher(line).find()) return true;
        }
        return false;
    }
}
