// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.task.ActionKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Dirty-memo inputs that are not a module's source fingerprint: the resolved JDK, member guard
 * rules, script bytes, and a root anchor's checkout scope.
 */
final class MemoInputs {

    private MemoInputs() {}

    /**
     * The release-file identity of the JDK this build compiles with — the same home
     * {@link TaskForecaster#forecastJavaHome} hands the compile key. Null when that home cannot
     * be resolved or its release file cannot be read, which is a memo miss.
     */
    static @Nullable String jdkReleaseToken(Path entryDir, BuildGraph.Result graph) {
        try {
            Path root = entryDir.toAbsolutePath().normalize();
            JkBuild build = null;
            Path manifest = ManifestPaths.manifestIn(root);
            if (Files.isRegularFile(manifest)) {
                build = JkBuildParser.parse(manifest);
            } else if (!graph.topoOrder().isEmpty()) {
                build = graph.topoOrder().getFirst().manifest();
            }
            if (build == null) return null;
            Path lockPath = LockPaths.lockFile(root);
            Lockfile lock = Files.isRegularFile(lockPath) ? LockfileReader.read(lockPath) : Lockfile.empty("preflight");
            Path home = TaskForecaster.forecastJavaHome(root, build, lock);
            if (home == null) return null;
            String token = ActionKey.jdkToken(home);
            if (token == null || token.isBlank() || "none".equals(token)) return null;
            return token;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception unreadable) {
            return null;
        }
    }

    /**
     * Member guard rules, script bytes, and — when a root anchor is present — every file that
     * anchor's action key already hashes. Null when one of those inputs will not read.
     */
    static @Nullable String logicToken(Path moduleDir) {
        try {
            MessageDigest md = Hashing.newSha256();
            PreflightMemo.feedFile(md, moduleDir.resolve(GuardsPresence.RULES_FILE));
            feedLogic(md, moduleDir);
            if (hasWorkspaceScopedScript(moduleDir)) {
                for (String token : BuildLogicSupport.workspaceInputTokens(moduleDir)) PreflightMemo.feed(md, token);
            }
            return Hashing.hex(md.digest());
        } catch (IOException unreadable) {
            return null;
        }
    }

    private static void feedLogic(MessageDigest md, Path moduleDir) throws IOException {
        for (Path dir : List.of(moduleDir.resolve("jk"), moduleDir.resolve(".jk"))) {
            if (!Files.isDirectory(dir)) continue;
            List<Path> files = new ArrayList<>();
            PathUtil.forEachRegularFile(dir, (file, attrs) -> files.add(file));
            files.sort(Comparator.comparing(Path::toString));
            for (Path file : files) PreflightMemo.feedFile(md, file);
        }
    }

    private static boolean hasWorkspaceScopedScript(Path moduleDir) throws IOException {
        for (Path dir : List.of(moduleDir.resolve("jk"), moduleDir.resolve(".jk"))) {
            for (BuildLogicScripts.ScriptTask task : BuildLogicScripts.discover(dir)) {
                if (task.anchor().workspaceScoped()) return true;
            }
        }
        return false;
    }
}
