// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.doctor;

import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.JkCacheConfig;
import cc.jumpkick.config.JkHttpConfig;
import cc.jumpkick.config.TomlScan;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.ProcessEnv;
import cc.jumpkick.lock.JumpKickPins;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.repo.M2Dirs;
import cc.jumpkick.repo.M2Integrity;
import cc.jumpkick.winhost.HostRow;
import cc.jumpkick.winhost.HostRow.Status;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The doctor rows any process on this host can answer: the user config, the JumpKick directories,
 * the state directory's mode, the JDK installs, a project's lock and its Maven-local copies. {@code
 * jk doctor} runs them in the client, MCP {@code doctor} and {@code GET /api/doctor} in the engine;
 * the rows only the client can answer (its own build, the shell hooks, the tool links, the Maven
 * extension, stored logins) are {@link #unavailable} on the engine's surfaces.
 */
public final class DoctorChecks {

    /** The rows only the client process can answer, in report order. */
    public static final List<String> CLIENT_LOCAL = List.of("client", "shell", "mvn", "logins", "tools");

    private DoctorChecks() {}

    /** The host rows for {@code project} (the lock and m2 rows read its lock), in report order. */
    public static List<HostRow> host(Path project, String version) {
        return List.of(
                config(JkDirs.userConfigFile(), version),
                dirs(JkDirs.cache(), JkDirs.store(), JkDirs.state(), JkCacheConfig.resolve()),
                stateMode(JkDirs.state()),
                jdk(JkDirs.jdks(), ProcessEnv.get("JAVA_HOME")),
                lock(project),
                pins(project, version),
                suiteReads(JkDirs.state()),
                m2(project));
    }

    /**
     * The last run of any module whose suite recordings could not be read, so that suite will not
     * replay: the engine notes it in {@code <state>/suite-reads-unread} and forgets it when that
     * module's next run is read. A suite that cannot replay runs on every build.
     */
    public static HostRow suiteReads(Path stateDir) {
        Path file = stateDir.resolve("suite-reads-unread");
        if (!Files.isRegularFile(file)) return row("suites", Status.OK, "every suite's last recording was read");
        try {
            String[] cells = Files.readString(file).strip().split("\t", 3);
            if (cells.length < 3) return row("suites", Status.WARN, "unreadable note at " + file);
            return new HostRow(
                    "suites",
                    "suites",
                    Status.WARN,
                    cells[1] + " (" + cells[0] + "): suite recording could not be read: " + cells[2]
                            + "; this suite will not replay",
                    "run its tests again; if it repeats, report it with the engine log");
        } catch (IOException | RuntimeException e) {
            return row("suites", Status.WARN, "could not read " + file + ": " + e.getMessage());
        }
    }

    /** A client-local row answered by a process that is not the client. */
    public static HostRow unavailable(String id) {
        return new HostRow(id, id, Status.UNAVAILABLE, "answered by jk doctor on the client", null);
    }

    /**
     * Whether {@code ~/.jk/config.toml} parses. A file that does not fails every command that
     * resolves, fetches or authenticates, so it is a failure here too, with the position to fix. A
     * config that parses is a warning when it names an {@code [http] web-overlay} this engine will
     * not serve.
     */
    public static HostRow config(Path file, String version) {
        if (!Files.exists(file)) return row("config", Status.OK, "no " + file + " (defaults)");
        var problem = GlobalConfig.userConfigProblem(file);
        if (problem.isPresent()) {
            return row("config", Status.FAIL, Objects.toString(problem.get().getMessage(), file.toString()));
        }
        String ignored = JkHttpConfig.fromToml(file)
                .flatMap(http -> http.webOverlayIgnored(version))
                .orElse(null);
        return ignored == null
                ? row("config", Status.OK, file + " parses")
                : row("config", Status.WARN, file + " parses · " + ignored);
    }

    /**
     * The cache directory is created by the first command that resolves something, so a fresh
     * install has none and that is healthy. A cache path that exists but is not a readable directory
     * is a real fault, as is a missing parent of the store or state directories.
     */
    public static HostRow dirs(Path cache, Path store, Path state, JkCacheConfig cfg) {
        List<String> problems = new ArrayList<>();
        boolean cacheAbsent = !Files.exists(cache);
        if (!cacheAbsent && !Files.isDirectory(cache)) problems.add("cache is not a directory: " + cache);
        if (!cacheAbsent && Files.isDirectory(cache) && !Files.isReadable(cache)) {
            problems.add("cache is not readable: " + cache);
        }
        Path storeParent = store.getParent();
        Path stateParent = state.getParent();
        if (storeParent == null || !Files.isDirectory(storeParent)) problems.add("store parent missing");
        if (stateParent == null || !Files.isDirectory(stateParent)) problems.add("state parent missing");
        if (!problems.isEmpty()) return row("dirs", Status.FAIL, String.join("; ", problems));
        String cacheWord = cacheAbsent ? "cache " + cache + " not created yet" : "cache " + cache;
        return row(
                "dirs",
                Status.OK,
                cacheWord + " · store " + store + " · " + JkCacheConfig.formatGb(cfg.maxCacheSizeGb())
                        + "G cache budget · store unbudgeted");
    }

    /**
     * The engine socket under {@code <state>/engine} is trusted on directory permissions alone, so
     * a state or engine directory that lets group or others in is a failure, with the chmod that
     * closes it. Non-POSIX filesystems (Windows, where the loopback token gates the engine) are OK.
     */
    public static HostRow stateMode(Path state) {
        Path engine = state.resolve("engine");
        List<String> loose = new ArrayList<>();
        for (Path dir : List.of(state, engine)) {
            if (!Files.isDirectory(dir)) continue;
            try {
                String mode = PosixFilePermissions.toString(Files.getPosixFilePermissions(dir));
                if (!"rwx------".equals(mode)) loose.add(dir + " is " + mode);
            } catch (UnsupportedOperationException notPosix) {
                return row("state", Status.OK, "no POSIX modes on this filesystem");
            } catch (IOException e) {
                return row("state", Status.WARN, "could not read: " + e.getMessage());
            }
        }
        if (loose.isEmpty()) return row("state", Status.OK, state + " owner-only");
        return new HostRow(
                "state",
                "state",
                Status.FAIL,
                String.join(", ", loose) + "; other users can reach the engine",
                "run chmod 700 " + state + " " + engine);
    }

    /** The JDK installs under {@code jdksDir} and the {@code JAVA_HOME} this process sees. */
    public static HostRow jdk(Path jdksDir, @Nullable String javaHome) {
        long[] count = {0};
        try {
            PathUtil.forEachChild(jdksDir, (child, attrs) -> {
                count[0]++;
                return true;
            });
        } catch (IOException e) {
            return row("jdk", Status.WARN, "probe failed: " + e.getMessage());
        }
        return row(
                "jdk",
                Status.OK,
                count[0] + " installs under " + jdksDir + (javaHome != null ? " · JAVA_HOME=" + javaHome : ""));
    }

    /**
     * How the project that governs {@code project} meets JDKs, as a note, never a fault: compile and
     * test keys name its Java level and the JDK that ran is a field of each cached result, which the
     * cache takes by its {@code jdk-match} level ({@code major} by default: any vendor and patch of
     * the JDK major this build uses). The row says which major the project builds on here and, when
     * no JDK of its own level is installed, that results cached on that level (as a CI runner on it
     * most likely made them) will not serve it, with the two ways out. A {@code jdk} requirement
     * made binding with {@code jdk-cache = "required"} is named.
     *
     * @param installedMajors the majors of the JDKs installed here
     * @param envMatch the shell's {@code JK_CACHE_JDK_MATCH}, or null
     * @param jkJdk the shell's {@code JK_JDK}, or null
     */
    public static HostRow jdkPatch(
            Path project, List<Integer> installedMajors, @Nullable String envMatch, @Nullable String jkJdk) {
        Path owner = LockPaths.lockOwnerDir(project);
        Path manifest = ManifestPaths.manifestIn(owner);
        if (!Files.isRegularFile(manifest)) return row("jdk-patch", Status.OK, "no jk.toml to check");
        TomlScan scan = TomlScan.scan(manifest, "jdk", "java", "jdk-cache", "cache.jdk-match");
        String match = envMatch != null && !envMatch.isBlank() ? envMatch.strip() : scan.get("cache.jdk-match");
        if (match == null || match.isBlank()) match = "major";
        if (jkJdk != null && !jkJdk.isBlank()) {
            return row(
                    "jdk-patch",
                    Status.OK,
                    "JK_JDK=" + jkJdk + " picks every build's JDK in this shell; cached results"
                            + " are taken from JDKs it matches (jdk-match = " + match + ")");
        }
        String jdk = scan.get("jdk");
        if (jdk != null && jdk.startsWith("=") && "required".equals(scan.get("jdk-cache"))) {
            return row(
                    "jdk-patch",
                    Status.OK,
                    "requires " + jdk.substring(1)
                            + " (jdk-cache = required): it builds only on it, and only results made on it serve it");
        }
        if (jdk != null && !jdk.isBlank()) {
            return row(
                    "jdk-patch",
                    Status.OK,
                    "pinned to " + jdk.replace("=", "")
                            + "; cached results are taken from JDKs it matches (jdk-match = " + match + ")");
        }
        int level = scan.getInt("java", 0);
        if (level <= 0) {
            return row(
                    "jdk-patch",
                    Status.OK,
                    "no java level: builds for the major of the JDK it runs on, and takes"
                            + " cached results made on that major (jdk-match = " + match + ")");
        }
        if (installedMajors.contains(level) || "level".equals(match)) {
            return row(
                    "jdk-patch",
                    Status.OK,
                    "Java " + level + ": builds on an installed JDK " + level
                            + (installedMajors.contains(level) ? "" : " or newer")
                            + " and takes cached results made on "
                            + ("level".equals(match) ? "any JDK " + level + " or newer" : "JDK " + level)
                            + " (jdk-match = " + match + ")");
        }
        int above = installedMajors.stream()
                .filter(m -> m > level)
                .min(Integer::compare)
                .orElse(-1);
        return row(
                "jdk-patch",
                Status.OK,
                "Java " + level + ": no JDK " + level + " installed"
                        + (above > 0 ? ", so it builds on JDK " + above : "")
                        + "; results cached on JDK " + level + " will not serve it: jk jdk install temurin-" + level
                        + ", or set [cache] jdk-match = \"level\"");
    }

    /** {@code =<vendor>-<a>.<b>.<c>} or {@code =<a>.<b>.<c>}: an exact pin down to the patch. */
    static boolean exactBuild(@Nullable String spec) {
        if (spec == null || !spec.startsWith("=")) return false;
        String v = spec.substring(spec.lastIndexOf('-') + 1);
        return v.chars().filter(c -> c == '.').count() >= 2;
    }

    /** The lock that governs {@code project}: present and of this format, with its artifact count. */
    public static HostRow lock(Path project) {
        Path lock = LockPaths.lockFile(project);
        Path owner = lock.getParent();
        if (!Files.isRegularFile(lock)) {
            return new HostRow("lock", "lock", Status.WARN, "no jk-lock.toml at " + owner, "run jk lock");
        }
        try {
            String text = Files.readString(lock);
            if (!text.contains("version = 1")) return row("lock", Status.WARN, "unexpected lock version");
            long artifacts = text.lines()
                    .filter(l -> l.trim().startsWith("[[artifact]]"))
                    .count();
            return row("lock", Status.OK, artifacts + " artifacts · " + lock);
        } catch (IOException e) {
            return row("lock", Status.WARN, "check failed: " + e.getMessage());
        }
    }

    /**
     * JumpKick's own pins in the project that governs {@code project} ({@link JumpKickPins}): rule
     * packs, the plugin SDK, a plugin's {@code sdk} and {@code jk-compat}. {@code WARN} naming each
     * one behind the running jk {@code version}, whose older releases JumpKick removes from its
     * repository; the fix is {@code jk update}, which moves them to this jk's release.
     */
    public static HostRow pins(Path project, String version) {
        try {
            Path owner = LockPaths.lockOwnerDir(project);
            List<JumpKickPins.Pin> declared = JumpKickPins.declared(owner);
            List<JumpKickPins.Pin> behind = declared.stream()
                    .filter(p -> JumpKickPins.behind(p.version(), version))
                    .toList();
            if (behind.isEmpty()) {
                return row(
                        "pins",
                        Status.OK,
                        declared.isEmpty()
                                ? "no JumpKick pins"
                                : declared.size() + " JumpKick " + (declared.size() == 1 ? "pin" : "pins")
                                        + " at this jk's release or newer");
            }
            return row("pins", Status.WARN, JumpKickPins.describe(owner, behind, version));
        } catch (RuntimeException e) {
            return row("pins", Status.WARN, "check failed: " + e.getMessage());
        }
    }

    /** The Maven local repository's copies of {@code project}'s locked files, checked against their {@code .sha1}. */
    public static HostRow m2(Path project) {
        try {
            Path lock = LockPaths.lockFile(project);
            if (!Files.isRegularFile(lock)) return row("m2", Status.OK, "no jk-lock.toml to check against");
            return m2(M2Dirs.localRepository(), M2Integrity.lockPaths(LockfileReader.read(lock)));
        } catch (IOException | RuntimeException e) {
            return row("m2", Status.WARN, "check failed: " + e.getMessage());
        }
    }

    /**
     * {@code WARN} naming the files under {@code m2} whose bytes do not match their {@code .sha1}:
     * something rewrote them after Maven verified them, and every build that reads one fails.
     */
    public static HostRow m2(Path m2, List<String> relativePaths) {
        List<Path> bad = M2Integrity.mismatched(m2, relativePaths);
        if (bad.isEmpty()) {
            return row("m2", Status.OK, relativePaths.size() + " locked files match their .sha1 under " + m2);
        }
        List<String> shown = new ArrayList<>();
        for (Path p : bad.subList(0, Math.min(3, bad.size()))) shown.add(p.toString());
        return new HostRow(
                "m2",
                "m2",
                Status.WARN,
                bad.size() + (bad.size() == 1 ? " file does" : " files do") + " not match its .sha1: "
                        + String.join(", ", shown) + (bad.size() > 3 ? ", …" : ""),
                "delete to fetch again");
    }

    private static HostRow row(String id, Status status, String detail) {
        return new HostRow(id, id, status, detail, null);
    }
}
