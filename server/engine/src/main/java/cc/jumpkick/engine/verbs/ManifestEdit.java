// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildEditor;
import cc.jumpkick.guard.eval.MutationCheck;
import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.library.LibraryCatalog;
import cc.jumpkick.lock.LockFreshness;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.Scope;
import cc.jumpkick.runtime.LockFlow;
import cc.jumpkick.runtime.StableVersions;
import cc.jumpkick.runtime.base.PolicyOps;
import cc.jumpkick.runtime.workspace.HeldFiles;
import cc.jumpkick.wire.protocol.DenyReport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The one {@code jk.toml} edit, for {@code jk add}/{@code jk remove}, MCP {@code deps}/{@code
 * workspace}/{@code manifest} and REST {@code POST /api/project/deps}: the ops through {@link
 * JkBuildEditor}, a dependency version checked against the project's repositories ({@code latest}
 * pinned to the newest stable), the proposed manifest judged by {@link MutationCheck}, then
 * (unless a preview) an atomic write and, for a dependency edit that asks, a relock whose new
 * deny-policy hits put {@code jk.toml} and {@code jk-lock.toml} back as they were.
 */
public final class ManifestEdit {

    /** One change to a manifest. */
    public sealed interface Op {
        /** Add {@code group:artifact} under {@code library}; {@code version} may be {@code latest}. */
        record AddDependency(
                Scope scope,
                String library,
                String group,
                String artifact,
                String version,
                @Nullable String classifier) implements Op {}

        /** Add a local jar, hashed into the CAS, as {@code group:artifact:version}. */
        record AddFileDependency(Scope scope, String library, String group, String artifact, String version, Path jar)
                implements Op {}

        /** Remove the dependency declared as {@code name}. */
        record RemoveDependency(Scope scope, String name) implements Op {}

        /** Rewrite the version of the dependency declared as {@code name}; the version must exist. */
        record PinDependency(Scope scope, String name, String group, String artifact, String version) implements Op {}

        /** Add {@code path} to {@code [workspace] modules}, creating the table when absent. */
        record AddWorkspaceModule(String path) implements Op {}

        /** Register {@code path} as a workspace member of an existing workspace. */
        record RegisterWorkspaceModule(String path) implements Op {}

        /** Remove {@code path} from the workspace members. */
        record RemoveWorkspaceModule(String path) implements Op {}

        /** Set {@code [artifacts]} assembly and minified. */
        record SetArtifacts(boolean assembly, boolean minified) implements Op {}

        /** Set the root {@code java} level. */
        record SetJava(int java) implements Op {}

        /** Whether this op edits a dependency table, so a lock depends on it. */
        default boolean dependency() {
            return this instanceof AddDependency
                    || this instanceof AddFileDependency
                    || this instanceof RemoveDependency
                    || this instanceof PinDependency;
        }

        /** Whether this op can bring in a new package, so the deny policy must see the new lock. */
        default boolean adds() {
            return this instanceof AddDependency || this instanceof AddFileDependency || this instanceof PinDependency;
        }
    }

    /**
     * Apply {@code ops} to {@code manifest}. {@code preview} writes nothing and does not lock;
     * {@code relock} relocks after a written dependency edit.
     */
    public record Request(Path manifest, List<Op> ops, boolean preview, boolean relock) {
        public Request {
            ops = List.copyOf(ops);
        }
    }

    /**
     * What the edit did. {@code preview} is the proposed manifest when it differs, else empty;
     * {@code notes} one line per op; {@code detail} the version written by a single add or the
     * sha256 of an added jar; {@code lock} {@code lock ok} or the failed relock's lines, null when
     * no relock ran; {@code denyHits} the deny rules a relock tripped (the edit was put back);
     * {@code error} why nothing was written.
     */
    public record Result(
            boolean changed,
            boolean applied,
            String preview,
            List<String> notes,
            String detail,
            @Nullable String lock,
            List<String> denyHits,
            @Nullable String error) {

        public Result {
            notes = List.copyOf(notes);
            denyHits = List.copyOf(denyHits);
        }

        static Result refused(List<String> notes, String detail, String error) {
            return new Result(false, false, "", notes, detail, null, List.of(), error);
        }

        /** Whether the lock a relock left is current. */
        public boolean lockOk() {
            return lock == null || LOCK_OK.equals(lock);
        }
    }

    /** The relock line when the lock is current. */
    public static final String LOCK_OK = "lock ok";

    private ManifestEdit() {}

    /** Run {@code req}; never throws, an error is the result's {@code error}. */
    public static Result run(Request req) {
        Path file = req.manifest();
        Path dir = Objects.requireNonNull(file.toAbsolutePath().getParent(), "manifest directory");
        if (ManifestPaths.isShadowed(dir)) {
            return Result.refused(List.of(), "", ManifestPaths.noManifestToEdit(dir.toString()));
        }
        List<String> notes = new ArrayList<>();
        String detail = "";
        try {
            String before = Files.readString(file, StandardCharsets.UTF_8);
            String after = before;
            LibraryCatalog catalog = null;
            for (Op op : req.ops()) {
                switch (op) {
                    case Op.AddDependency a -> {
                        if (!"latest".equals(a.version())) {
                            StableVersions.requireExists(file, a.group(), a.artifact(), a.version());
                        }
                        String version = StableVersions.versionToWrite(file, a.group(), a.artifact(), a.version());
                        if (catalog == null) catalog = LibraryCatalog.forProject(dir);
                        after = JkBuildEditor.addDependency(
                                after,
                                a.scope(),
                                a.library(),
                                a.group(),
                                a.artifact(),
                                version,
                                a.classifier(),
                                catalog);
                        detail = version;
                        notes.add(
                                Dependency.MANAGED_KEYWORD.equals(version)
                                        ? "add " + a.group() + ":" + a.artifact() + " (version managed by the platform)"
                                        : "add " + a.group() + ":" + a.artifact() + ":" + version);
                    }
                    case Op.AddFileDependency f -> {
                        String sha256 = req.preview()
                                ? Hashing.sha256Hex(f.jar())
                                : JkStores.storeCas().putFile(f.jar()).sha256();
                        after = JkBuildEditor.addFileDependency(
                                after, f.scope(), f.library(), f.group(), f.artifact(), f.version(), sha256);
                        detail = sha256;
                        notes.add("add " + f.group() + ":" + f.artifact() + ":" + f.version() + " (" + f.jar() + ")");
                    }
                    case Op.RemoveDependency r -> {
                        after = JkBuildEditor.removeDependency(after, r.scope(), r.name());
                        notes.add("remove " + r.name());
                    }
                    case Op.PinDependency p -> {
                        StableVersions.requireExists(file, p.group(), p.artifact(), p.version());
                        after = JkBuildEditor.setDependencyVersion(after, p.scope(), p.name(), p.version());
                        notes.add("pin " + p.group() + ":" + p.artifact() + ":" + p.version());
                    }
                    case Op.AddWorkspaceModule w -> {
                        after = JkBuildEditor.addWorkspaceModule(after, w.path());
                        notes.add("add member " + w.path());
                    }
                    case Op.RegisterWorkspaceModule w -> {
                        after = JkBuildEditor.registerWorkspaceModule(after, w.path());
                        notes.add("add member " + w.path());
                    }
                    case Op.RemoveWorkspaceModule w -> {
                        after = JkBuildEditor.removeWorkspaceModule(after, w.path());
                        notes.add("remove member " + w.path());
                    }
                    case Op.SetArtifacts s -> {
                        after = JkBuildEditor.setArtifacts(after, s.assembly(), s.minified());
                        notes.add("artifacts assembly=" + s.assembly() + " minified=" + s.minified());
                    }
                    case Op.SetJava j -> {
                        after = JkBuildEditor.setRootScalar(after, "java", String.valueOf(j.java()));
                        notes.add("java = " + j.java() + " (language level, not a jdk pin)");
                    }
                }
            }
            boolean changed = !after.equals(before);
            if (!changed) return new Result(false, false, "", notes, detail, null, List.of(), null);
            // Dependency-policy guards judge the proposed manifest before it is written; a violation
            // refuses the edit with `instead` and `why`. No flag overrides this.
            String refusal = MutationCheck.check(file, after);
            if (refusal != null) return Result.refused(notes, detail, refusal);
            if (req.preview()) return new Result(true, false, after, notes, detail, null, List.of(), null);
            boolean relock = req.relock() && req.ops().stream().anyMatch(Op::dependency);
            boolean gate = relock && req.ops().stream().anyMatch(Op::adds);
            Path lockDir = LockPaths.lockOwnerDir(dir);
            HeldFiles held = relock ? HeldFiles.of(List.of(file, LockPaths.lockFile(dir))) : null;
            List<String> denyBefore = gate ? denyHits(lockDir) : List.of();
            AtomicWrites.replace(file, after); // a concurrently-parsing build must never see a torn jk.toml
            if (!relock) return new Result(true, true, after, notes, detail, null, List.of(), null);
            String lock = relock(lockDir);
            if (!LOCK_OK.equals(lock)) return new Result(true, true, after, notes, detail, lock, List.of(), null);
            List<String> hits = new ArrayList<>(gate ? denyHits(lockDir) : List.of());
            hits.removeAll(denyBefore);
            if (hits.isEmpty()) return new Result(true, true, after, notes, detail, lock, List.of(), null);
            Objects.requireNonNull(held).restore();
            return new Result(
                    false,
                    false,
                    "",
                    notes,
                    detail,
                    lock,
                    hits,
                    "deny policy refuses the edit; nothing written:\n" + String.join("\n", hits));
        } catch (IOException | RuntimeException e) {
            return Result.refused(notes, detail, Errors.text(e));
        }
    }

    /** {@code result} as the JSON object REST answers and MCP's structured view carries. */
    public static Map<String, Object> structured(Result result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("changed", result.changed());
        m.put("applied", result.applied());
        m.put("notes", result.notes());
        if (!result.preview().isEmpty()) m.put("preview", result.preview());
        if (!result.detail().isEmpty()) m.put("detail", result.detail());
        if (result.lock() != null) m.put("lock", result.lock());
        if (!result.denyHits().isEmpty()) m.put("denyHits", result.denyHits());
        if (result.error() != null) m.put("error", result.error());
        return m;
    }

    /**
     * The dependency ops a coordinate request asks for ({@code action} add, remove or pin over
     * {@code group:artifact[:version]} coordinates), as MCP {@code deps} and REST send them. A
     * coordinate an action cannot use is refused naming it.
     */
    public static List<Op> coordOps(String action, List<String> coords, @Nullable String scopeName) {
        Scope scope = parseScope(scopeName);
        if (coords.isEmpty()) throw new IllegalArgumentException("coords is empty");
        List<Op> ops = new ArrayList<>();
        for (String c : coords) {
            String[] p = c.strip().split(":", -1);
            switch (action) {
                case "remove" -> ops.add(new Op.RemoveDependency(scope, p.length >= 2 ? p[1] : p[0]));
                case "pin" -> {
                    if (p.length < 3 || p[0].isBlank() || p[2].isBlank()) {
                        throw new IllegalArgumentException("pin needs group:artifact:version: " + c);
                    }
                    ops.add(new Op.PinDependency(scope, p[1], p[0], p[1], p[2]));
                }
                case "add" -> {
                    if (p.length < 2 || p[0].isBlank() || p[1].isBlank()) {
                        throw new IllegalArgumentException("add needs group:artifact[:version]: " + c);
                    }
                    String version = p.length >= 3 && !p[2].isBlank() ? p[2] : "latest";
                    ops.add(new Op.AddDependency(scope, p[1], p[0], p[1], version, null));
                }
                default -> throw new IllegalArgumentException("action must be add, remove or pin");
            }
        }
        return ops;
    }

    /**
     * A scope name, {@code main} when blank. An unknown name is an error, never a default: a
     * {@code runtime} request silently rewritten to {@code main} is a wrong dependency.
     */
    public static Scope parseScope(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return Scope.MAIN;
        String canonical = raw.trim().toLowerCase(Locale.ROOT);
        try {
            return Scope.fromCanonical(canonical);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown scope '" + raw + "' — use one of "
                    + Arrays.stream(Scope.values()).map(Scope::canonical).collect(Collectors.joining(", ")));
        }
    }

    /**
     * Relock {@code dir} keeping its pins: {@link #LOCK_OK}, or {@code FAIL lock <dir>} and the
     * first lines of the resolver's message. A lock that is already current is not resolved twice.
     */
    public static String relock(Path dir) {
        try {
            LockFlow.Result result = LockFlow.run(dir, JkDirs.cache(), List.of(), false, null);
            if (result.status() == 0 && !LockFreshness.needsRefresh(dir)) return LOCK_OK;
            return lockFailure(dir, result.error() == null ? "lock failed" : result.error());
        } catch (Exception e) {
            return lockFailure(dir, Errors.text(e));
        }
    }

    /** {@code FAIL lock <dir>} plus the first three lines of the resolver's message. */
    static String lockFailure(Path dir, String error) {
        String leaf = dir.getFileName() == null ? "" : dir.getFileName().toString();
        StringBuilder sb = new StringBuilder("FAIL lock");
        if (!leaf.isEmpty()) sb.append(' ').append(leaf);
        int n = 0;
        for (String raw : error.split("\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            sb.append('\n').append(n == 0 ? "E " : "  ").append(line);
            if (++n == 3) break;
        }
        if (n == 0) sb.append("\nE resolve failed");
        return sb.toString();
    }

    /** What the deny policy says about the lock {@code root} has now, one line per hit. */
    private static List<String> denyHits(Path root) {
        DenyReport report = PolicyOps.denyCheck(root);
        List<String> hits = new ArrayList<>();
        for (int i = 0; i < report.violationCount(); i++) {
            hits.add(report.modules().get(i) + " " + report.versions().get(i) + ": "
                    + report.reasons().get(i));
        }
        return hits;
    }
}
