// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.host.JkDirs;
import cc.jumpkick.ide.IdeModel;
import cc.jumpkick.ide.JdtProjects;
import cc.jumpkick.runtime.IdeModels;
import cc.jumpkick.wire.protocol.IdeWireModel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The body of {@code GET /api/project/model}: the engine's IDE model (the one {@link IdeModels}
 * serves {@code jk ide}, the IntelliJ plugin and {@code jk bsp}) as one JDT project per module
 * ({@link JdtProjects}). Paths are absolute; entries keep JDT's raw-classpath order. Field
 * reference: {@code docs/contributors/http.md}.
 */
final class ProjectModelJson {

    private ProjectModelJson() {}

    /** The rendered model of {@code dir}, or a map holding only {@code error}. */
    static Map<String, Object> forDir(Path dir) {
        return of(IdeModels.hosted(dir, JkDirs.cache(), null, true));
    }

    /** {@code wire} rendered, or a map holding only its {@code error}. */
    static Map<String, Object> of(IdeWireModel wire) {
        String error = wire.error();
        if (error != null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("error", error);
            return m;
        }
        return render(wire.workspace(), IdeModel.fromWire(wire, null));
    }

    static Map<String, Object> render(boolean workspace, IdeModel model) {
        List<Object> projects = new ArrayList<>();
        for (JdtProjects.Project p : JdtProjects.of(model)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", p.name());
            row.put("dir", abs(p.dir()));
            row.put("compliance", p.compliance());
            Path jdk = p.jdkHome();
            row.put("jdkHome", jdk == null ? null : abs(jdk));
            row.put("output", abs(p.output()));
            List<Object> entries = new ArrayList<>();
            for (JdtProjects.Entry e : p.entries()) entries.add(entry(e));
            row.put("entries", entries);
            projects.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("root", abs(model.wsRoot()));
        body.put("name", model.rootName());
        body.put("workspace", workspace);
        body.put("projects", projects);
        return body;
    }

    private static Map<String, Object> entry(JdtProjects.Entry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        switch (e) {
            case JdtProjects.Source s -> {
                m.put("kind", "source");
                m.put("path", abs(s.path()));
                m.put("output", abs(s.output()));
                m.put("test", s.test());
                m.put("generated", s.generated());
            }
            case JdtProjects.ProjectRef r -> {
                m.put("kind", "project");
                m.put("name", r.name());
                m.put("test", r.test());
            }
            case JdtProjects.Library l -> {
                m.put("kind", "library");
                m.put("path", abs(l.path()));
                m.put("sources", sources(l.sources()));
                m.put("test", l.test());
            }
            case JdtProjects.SiblingTests t -> {
                m.put("kind", "library");
                m.put("path", abs(t.classes()));
                m.put("sources", null);
                m.put("test", true);
            }
            case JdtProjects.Container c -> {
                m.put("kind", "container");
                m.put("path", c.path());
            }
        }
        return m;
    }

    private static @Nullable String sources(@Nullable Path p) {
        return p == null ? null : abs(p);
    }

    private static String abs(Path p) {
        return p.toAbsolutePath().normalize().toString();
    }
}
