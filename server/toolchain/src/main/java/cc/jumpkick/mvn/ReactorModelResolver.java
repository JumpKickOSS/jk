// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.resolution.WorkspaceModelResolver;
import org.jspecify.annotations.Nullable;

/**
 * Parent lookup inside one Maven reactor: the root pom.xml and every {@code <module>} pom.xml,
 * keyed by their coordinates. Maven asks here before {@code <relativePath>} or any repository, so a
 * sibling parent is never fetched from the network — and a module whose {@code <relativePath/>} is
 * empty still finds it.
 */
final class ReactorModelResolver implements WorkspaceModelResolver {

    private final Map<String, Model> byGav = new LinkedHashMap<>();

    /** Register one pom.xml of the reactor. */
    void add(Path pomFile, Model raw) {
        Model model = raw.clone();
        model.setPomFile(pomFile.toFile());
        byGav.put(gav(raw), model);
    }

    boolean contains(String gav) {
        return byGav.containsKey(gav);
    }

    @Override
    public @Nullable Model resolveRawModel(String groupId, String artifactId, String versionConstraint) {
        Model hit = byGav.get(groupId + ":" + artifactId + ":" + versionConstraint);
        return hit == null ? null : hit.clone();
    }

    /** Maven falls back to the raw model and assembles it itself. */
    @Override
    public @Nullable Model resolveEffectiveModel(String groupId, String artifactId, String versionConstraint) {
        return null;
    }

    /** {@code g:a:v} with {@code <parent>} filling a missing groupId or version, as Maven does. */
    static String gav(Model raw) {
        Parent parent = raw.getParent();
        String group = raw.getGroupId() != null ? raw.getGroupId() : parent != null ? parent.getGroupId() : null;
        String version = raw.getVersion() != null ? raw.getVersion() : parent != null ? parent.getVersion() : null;
        return group + ":" + raw.getArtifactId() + ":" + version;
    }
}
