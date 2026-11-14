// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdtls;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The engine's {@code GET /api/project/model} body (docs/contributors/http.md), read into the
 * plan this bundle applies: one project per module, its source and output folders relative to
 * the project, everything else as the engine wrote it. Free of Eclipse types so it is tested on
 * its own.
 */
public record ProjectModel(String root, List<Project> projects) {

    /** A module's project; {@code output} is relative to {@code dir}. */
    public record Project(String name, Path dir, String compliance, String output, List<Entry> entries) {}

    /** A raw classpath entry, in the engine's order. */
    public sealed interface Entry {}

    /** A source folder inside the project; {@code optional} for one that may not exist yet. */
    public record Source(String path, String output, boolean test, boolean optional) implements Entry {}

    public record ProjectRef(String name, boolean test) implements Entry {}

    public record Library(String path, String sources, boolean test) implements Entry {}

    public record Container(String path) implements Entry {}

    private record Wire(String root, List<WireProject> projects) {}

    private record WireProject(String name, String dir, String compliance, String output, List<WireEntry> entries) {}

    private record WireEntry(
            String kind, String path, String output, String name, String sources, boolean test, boolean generated) {}

    /** Parse the engine's JSON; a folder outside its project is an error, never a silent link. */
    public static ProjectModel parse(String json) {
        Wire wire;
        try {
            wire = new Gson().fromJson(json, Wire.class);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("the JumpKick model is not valid JSON: " + e.getMessage(), e);
        }
        if (wire == null || wire.root() == null || wire.projects() == null) {
            throw new IllegalArgumentException("the JumpKick model has no root or projects");
        }
        List<Project> projects = new ArrayList<>();
        for (WireProject p : wire.projects()) {
            Path dir = Path.of(p.dir()).normalize();
            List<Entry> entries = new ArrayList<>();
            for (WireEntry e : p.entries() == null ? List.<WireEntry>of() : p.entries()) {
                entries.add(switch (e.kind()) {
                    case "source" ->
                        new Source(inside(dir, e.path(), p.name()), inside(dir, e.output(), p.name()), e.test(), e.generated());
                    case "project" -> new ProjectRef(e.name(), e.test());
                    case "library" -> new Library(e.path(), e.sources(), e.test());
                    case "container" -> new Container(e.path());
                    default -> throw new IllegalArgumentException("unknown classpath entry kind: " + e.kind());
                });
            }
            projects.add(new Project(p.name(), dir, p.compliance(), inside(dir, p.output(), p.name()), List.copyOf(entries)));
        }
        return new ProjectModel(Path.of(wire.root()).normalize().toString(), List.copyOf(projects));
    }

    /** {@code path} relative to {@code dir}, forward slashes. */
    private static String inside(Path dir, String path, String project) {
        Path p = Path.of(path).normalize();
        if (!p.startsWith(dir)) {
            throw new IllegalArgumentException(path + " is outside project " + project + " (" + dir + ")");
        }
        return dir.relativize(p).toString().replace('\\', '/');
    }
}
