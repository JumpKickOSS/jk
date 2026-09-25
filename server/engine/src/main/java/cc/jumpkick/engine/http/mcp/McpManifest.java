// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.config.JkBuildEditor;
import cc.jumpkick.guard.eval.MutationCheck;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.library.LibraryCatalog;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.Scope;
import cc.jumpkick.runtime.StableVersions;
import cc.jumpkick.util.AtomicWrites;
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
 * Surgical jk.toml edits (preview by default). Dependencies are spelled by the same writer as
 * {@code jk add}; a {@code group:artifact} without a version is pinned to its newest stable release.
 */
public final class McpManifest {

    private McpManifest() {}

    public static Map<String, Object> deps(
            String dir, String action, List<String> coords, @Nullable String scopeName, boolean apply) {
        Path file = PathUtil.resolveUserPath(dir).resolve(ManifestPaths.MANIFEST);
        Map<String, Object> out = new LinkedHashMap<>();
        if (refuseShadowed(file, out)) return out;
        try {
            String before = Files.readString(file, StandardCharsets.UTF_8);
            String after = before;
            Scope scope = parseScope(scopeName);
            List<String> notes = new ArrayList<>();
            if ("remove".equals(action)) {
                for (String c : coords) {
                    String name = artifactName(c);
                    after = JkBuildEditor.removeDependency(after, scope, name);
                    notes.add("remove " + name);
                }
            } else if ("pin".equals(action)) {
                for (String c : coords) {
                    Parsed p = parseCoord(c);
                    if (p.version == null || p.group.isBlank()) {
                        notes.add("skip " + c + " (pin needs group:artifact:version)");
                        continue;
                    }
                    try {
                        after = JkBuildEditor.setDependencyVersion(after, scope, p.name, p.version);
                        notes.add("pin " + p.group + ":" + p.artifact + ":" + p.version);
                    } catch (RuntimeException e) {
                        notes.add("skip " + c + " (" + e.getMessage() + ")");
                    }
                }
            } else {
                LibraryCatalog catalog = LibraryCatalog.forProject(
                        Objects.requireNonNull(file.toAbsolutePath().getParent(), "manifest directory"));
                for (String c : coords) {
                    Parsed p = parseCoord(c);
                    if (p.group.isBlank()) {
                        notes.add("skip " + c + " (need group:artifact[:version])");
                        continue;
                    }
                    String version = StableVersions.versionToWrite(
                            file, p.group, p.artifact, p.version == null ? "latest" : p.version);
                    after = JkBuildEditor.addDependency(after, scope, p.name, p.group, p.artifact, version, catalog);
                    notes.add(
                            Dependency.MANAGED_KEYWORD.equals(version)
                                    ? "add " + p.group + ":" + p.artifact + " (version managed by the platform)"
                                    : "add " + p.group + ":" + p.artifact + ":" + version);
                }
            }
            String refusal = after.equals(before) ? null : MutationCheck.check(file, after);
            if (refusal != null) {
                // The guard refused the proposed manifest: nothing changes, and the refusal is the note.
                notes.add(refusal);
                out.put("notes", notes);
                out.put("changed", false);
                out.put("preview", "");
                out.put("applied", false);
                return out;
            }
            out.put("notes", notes);
            out.put("changed", !after.equals(before));
            out.put("preview", after.equals(before) ? "" : after);
            if (apply && !after.equals(before)) {
                AtomicWrites.replace(file, after); // a concurrently-parsing build must never see a torn jk.toml
                out.put("applied", true);
            } else {
                out.put("applied", false);
            }
        } catch (Exception e) {
            out.put("error", Errors.text(e));
        }
        return out;
    }

    public static Map<String, Object> workspace(String dir, String action, String path, boolean apply) {
        Path file = PathUtil.resolveUserPath(dir).resolve(ManifestPaths.MANIFEST);
        Map<String, Object> out = new LinkedHashMap<>();
        if (refuseShadowed(file, out)) return out;
        try {
            String before = Files.readString(file, StandardCharsets.UTF_8);
            String after = "remove_member".equals(action)
                    ? JkBuildEditor.removeWorkspaceModule(before, path)
                    : JkBuildEditor.registerWorkspaceModule(before, path);
            out.put("changed", !after.equals(before));
            out.put("preview", after.equals(before) ? "" : after);
            if (apply && !after.equals(before)) {
                AtomicWrites.replace(file, after); // a concurrently-parsing build must never see a torn jk.toml
                out.put("applied", true);
            } else {
                out.put("applied", false);
            }
        } catch (Exception e) {
            out.put("error", Errors.text(e));
        }
        return out;
    }

    public static Map<String, Object> setJava(String dir, int java, boolean apply) {
        Path file = PathUtil.resolveUserPath(dir).resolve(ManifestPaths.MANIFEST);
        Map<String, Object> out = new LinkedHashMap<>();
        if (refuseShadowed(file, out)) return out;
        out.put("note", "java = " + java + " is language/--release, not jdk = " + java);
        try {
            String before = Files.readString(file, StandardCharsets.UTF_8);
            // Through the manifest writer, not a regex: the edit is validated as TOML before it is
            // offered as a preview, so MCP cannot propose a jk.toml the build would refuse.
            String after = JkBuildEditor.setRootScalar(before, "java", String.valueOf(java));
            out.put("changed", !after.equals(before));
            out.put("preview", after);
            if (apply) {
                AtomicWrites.replace(file, after); // a concurrently-parsing build must never see a torn jk.toml
                out.put("applied", true);
            } else {
                out.put("applied", false);
            }
        } catch (Exception e) {
            out.put("error", Errors.text(e));
        }
        return out;
    }

    /**
     * An unparseable scope from an agent is an error, not a default: swallowing it into
     * {@code MAIN} silently rewrote a {@code runtime} request into a main dependency. The throw
     * rides the caller's {@code error} field back to the MCP client.
     */
    static Scope parseScope(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return Scope.MAIN;
        String canonical = raw.trim().toLowerCase(Locale.ROOT);
        try {
            return Scope.fromCanonical(canonical);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown scope '" + raw + "' — use one of "
                    + Arrays.stream(Scope.values()).map(Scope::canonical).collect(Collectors.joining(", ")));
        }
    }

    private static String artifactName(String coord) {
        String[] p = coord.split(":");
        return p.length >= 2 ? p[1] : coord;
    }

    private record Parsed(
            String name,
            String group,
            String artifact,
            @Nullable String version) {}

    private static Parsed parseCoord(String raw) {
        String[] p = raw.split(":");
        if (p.length >= 3) return new Parsed(p[1], p[0], p[1], p[2]);
        if (p.length == 2) return new Parsed(p[1], p[0], p[1], null);
        return new Parsed(raw, "", raw, null);
    }
    /**
     * A directory built in place from its {@code pom.xml} has no manifest to write: the answer is
     * the error naming the two remedies, and {@code true} so the caller returns it.
     */
    static boolean refuseShadowed(Path manifest, Map<String, Object> out) {
        Path dir = Objects.requireNonNull(manifest.getParent(), "manifest directory");
        if (!ManifestPaths.isShadowed(dir)) return false;
        out.put("error", ManifestPaths.noManifestToEdit(dir.toString()));
        out.put("applied", false);
        return true;
    }
}
