// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.EnvValues;
import cc.jumpkick.task.CachePlatform;
import com.sun.management.OperatingSystemMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Where a run came from, as the run itself can tell: the repository and branch of its checkout,
 * the CI system, run and event that started it, and the class of host it ran on. Display claims
 * for history and exported telemetry, never used to decide anything: a CI server's variables are
 * whatever the shell that ran {@code jk} says they are.
 *
 * @param repository the checkout's remote, as {@code https://host/path} without credentials or
 *     {@code .git}; null outside git or without a remote
 * @param branch the branch the checkout is on, or the branch a CI run builds; null when detached
 * @param ciProvider {@code github-actions}, {@code gitlab-ci}, {@code buildkite}, {@code circleci},
 *     or {@code ci} for a CI server this jk does not name; null on a developer's machine
 * @param ciRunId the CI system's id of the run
 * @param ciRunUrl where the CI run's page is
 * @param ciEvent what started the CI run ({@code push}, {@code pull_request}, {@code schedule}, …)
 * @param host the host's {@code <os>-<arch>}
 * @param cpus the processors the engine sees
 * @param memoryBytes the host's physical memory, {@code 0} when unknown
 */
public record RunSource(
        @Nullable String repository,
        @Nullable String branch,
        @Nullable String ciProvider,
        @Nullable String ciRunId,
        @Nullable String ciRunUrl,
        @Nullable String ciEvent,
        @Nullable String host,
        int cpus,
        long memoryBytes) {

    /** The run of the request bound now, in checkout {@code dir}: its shell's CI variables, its git checkout. */
    public static RunSource ofRequest(Path dir) {
        return of(dir, BuildEnv::get);
    }

    /** As {@link #ofRequest} with the CI variables read from {@code env}. */
    static RunSource of(Path dir, Function<String, @Nullable String> env) {
        Git git = Git.of(dir);
        String repository = git.repository();
        String branch = git.branch();
        String provider = null;
        String runId = null;
        String runUrl = null;
        String event = null;
        if (on(env, "GITHUB_ACTIONS")) {
            provider = "github-actions";
            runId = env.apply("GITHUB_RUN_ID");
            String server = env.apply("GITHUB_SERVER_URL");
            String repo = env.apply("GITHUB_REPOSITORY");
            if (server != null && repo != null) {
                repository = remote(server + "/" + repo);
                if (runId != null) runUrl = server + "/" + repo + "/actions/runs/" + runId;
            }
            event = env.apply("GITHUB_EVENT_NAME");
            branch = first(env.apply("GITHUB_HEAD_REF"), env.apply("GITHUB_REF_NAME"), branch);
        } else if (on(env, "GITLAB_CI")) {
            provider = "gitlab-ci";
            runId = env.apply("CI_PIPELINE_ID");
            runUrl = env.apply("CI_PIPELINE_URL");
            event = env.apply("CI_PIPELINE_SOURCE");
            branch = first(env.apply("CI_MERGE_REQUEST_SOURCE_BRANCH_NAME"), env.apply("CI_COMMIT_REF_NAME"), branch);
            String project = env.apply("CI_PROJECT_URL");
            if (project != null) repository = remote(project);
        } else if (on(env, "BUILDKITE")) {
            provider = "buildkite";
            runId = env.apply("BUILDKITE_BUILD_ID");
            runUrl = env.apply("BUILDKITE_BUILD_URL");
            event = env.apply("BUILDKITE_SOURCE");
            branch = first(env.apply("BUILDKITE_BRANCH"), branch);
        } else if (on(env, "CIRCLECI")) {
            provider = "circleci";
            runId = first(env.apply("CIRCLE_WORKFLOW_ID"), env.apply("CIRCLE_BUILD_NUM"));
            runUrl = env.apply("CIRCLE_BUILD_URL");
            branch = first(env.apply("CIRCLE_BRANCH"), branch);
        } else if (EnvValues.isCi(env)) {
            provider = "ci";
        }
        return new RunSource(
                repository,
                blank(branch),
                provider,
                blank(runId),
                blank(runUrl),
                blank(event),
                CachePlatform.current(),
                Runtime.getRuntime().availableProcessors(),
                physicalMemory());
    }

    /** The JSON object the journal writes: only the fields that are known. */
    Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (repository != null) m.put("repository", repository);
        if (branch != null) m.put("branch", branch);
        if (ciProvider != null) m.put("ciProvider", ciProvider);
        if (ciRunId != null) m.put("ciRunId", ciRunId);
        if (ciRunUrl != null) m.put("ciRunUrl", ciRunUrl);
        if (ciEvent != null) m.put("ciEvent", ciEvent);
        if (host != null) m.put("host", host);
        if (cpus > 0) m.put("cpus", cpus);
        if (memoryBytes > 0) m.put("memoryBytes", memoryBytes);
        return m;
    }

    /** The source a journal's {@code source} object holds. */
    static RunSource fromMap(Map<?, ?> m) {
        return new RunSource(
                text(m.get("repository")),
                text(m.get("branch")),
                text(m.get("ciProvider")),
                text(m.get("ciRunId")),
                text(m.get("ciRunUrl")),
                text(m.get("ciEvent")),
                text(m.get("host")),
                m.get("cpus") instanceof Number n ? n.intValue() : 0,
                m.get("memoryBytes") instanceof Number n ? n.longValue() : 0);
    }

    private static @Nullable String text(@Nullable Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    /** {@code url} as the repository is named: {@code https://host/path}, no credentials, no {@code .git}, lower case. */
    static @Nullable String remote(@Nullable String url) {
        if (url == null || url.isBlank()) return null;
        String r = url.trim();
        if (r.startsWith("git@")) {
            int colon = r.indexOf(':');
            if (colon > 4) r = "https://" + r.substring(4, colon) + "/" + r.substring(colon + 1);
        } else if (r.startsWith("ssh://")) {
            r = "https://" + r.substring("ssh://".length());
        }
        int scheme = r.indexOf("://");
        if (scheme > 0) {
            int at = r.indexOf('@', scheme + 3);
            int slash = r.indexOf('/', scheme + 3);
            if (at > 0 && (slash < 0 || at < slash)) r = r.substring(0, scheme + 3) + r.substring(at + 1);
        }
        if (r.endsWith("/")) r = r.substring(0, r.length() - 1);
        if (r.endsWith(".git")) r = r.substring(0, r.length() - 4);
        return r.toLowerCase(Locale.ROOT);
    }

    private static boolean on(Function<String, @Nullable String> env, String name) {
        return EnvValues.bool(env, name).orElse(false);
    }

    private static @Nullable String first(@Nullable String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static @Nullable String blank(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static long physicalMemory() {
        try {
            if (ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean os)
                return os.getTotalMemorySize();
        } catch (RuntimeException | LinkageError unavailable) {
            // a host that does not say
        }
        return 0;
    }

    /** The checkout's branch and remote, read from its git files: no process. */
    private record Git(@Nullable String branch, @Nullable String repository) {

        static Git of(Path dir) {
            try {
                for (Path d = dir.toAbsolutePath().normalize(); d != null; d = d.getParent()) {
                    Path dotGit = d.resolve(".git");
                    if (Files.isDirectory(dotGit)) return read(dotGit, dotGit);
                    if (Files.isRegularFile(dotGit)) {
                        String line = Files.readString(dotGit).trim();
                        if (!line.startsWith("gitdir:")) return new Git(null, null);
                        Path gitDir = d.resolve(
                                        line.substring("gitdir:".length()).trim())
                                .normalize();
                        Path common = gitDir;
                        Path commonFile = gitDir.resolve("commondir");
                        if (Files.isRegularFile(commonFile))
                            common = gitDir.resolve(Files.readString(commonFile).trim())
                                    .normalize();
                        return read(gitDir, common);
                    }
                }
            } catch (IOException | RuntimeException unreadable) {
                // not a checkout this can read: no claim
            }
            return new Git(null, null);
        }

        private static Git read(Path gitDir, Path common) throws IOException {
            String branch = null;
            Path head = gitDir.resolve("HEAD");
            if (Files.isRegularFile(head)) {
                String ref = Files.readString(head).trim();
                if (ref.startsWith("ref: refs/heads/")) branch = ref.substring("ref: refs/heads/".length());
            }
            return new Git(branch, remote(originUrl(common.resolve("config"))));
        }

        /** The url of remote {@code origin}, else of the first remote, in a git config file. */
        private static @Nullable String originUrl(Path config) throws IOException {
            if (!Files.isRegularFile(config)) return null;
            String section = null;
            String firstUrl = null;
            for (String raw : Files.readAllLines(config)) {
                String line = raw.trim();
                if (line.startsWith("[")) {
                    section = line;
                } else if (section != null && section.startsWith("[remote ") && line.startsWith("url")) {
                    int eq = line.indexOf('=');
                    if (eq < 0) continue;
                    String url = line.substring(eq + 1).trim();
                    if (section.equals("[remote \"origin\"]")) return url;
                    if (firstUrl == null) firstUrl = url;
                }
            }
            return firstUrl;
        }
    }
}
