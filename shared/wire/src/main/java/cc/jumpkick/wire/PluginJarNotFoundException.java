// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire;

import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Thrown when a worker jar is absent from the local and central repos and no {@code -D} override resolves it. */
public final class PluginJarNotFoundException extends RuntimeException {

    private final String artifactId;
    private final String coordinate;
    private final List<Path> pathsChecked;
    private final String jarProperty;

    public PluginJarNotFoundException(
            String artifactId, String coordinate, List<Path> pathsChecked, String jarProperty) {
        this(artifactId, coordinate, pathsChecked, jarProperty, null);
    }

    public PluginJarNotFoundException(
            String artifactId,
            String coordinate,
            List<Path> pathsChecked,
            String jarProperty,
            @Nullable String detail) {
        super(buildMessage(artifactId, coordinate, pathsChecked, jarProperty, detail));
        this.artifactId = artifactId;
        this.coordinate = coordinate;
        this.pathsChecked = List.copyOf(pathsChecked);
        this.jarProperty = jarProperty;
    }

    private static String buildMessage(
            String artifactId,
            String coordinate,
            List<Path> pathsChecked,
            String jarProperty,
            @Nullable String detail) {
        var sb = new StringBuilder();
        sb.append(artifactId)
                .append(".jar not found for coordinate ")
                .append(coordinate)
                .append('\n');
        sb.append("Paths checked:\n");
        for (Path p : pathsChecked) {
            sb.append("  ").append(p).append('\n');
        }
        if (detail != null && !detail.isBlank()) {
            sb.append(detail).append('\n');
        }
        sb.append("Run `jk install` in jk's own tree (it shelves ")
                .append(artifactId)
                .append(")");
        sb.append(" or set -D").append(jarProperty).append(" to override.");
        sb.append("\nOfficial repo: https://jumpkick.build/repo/");
        return sb.toString();
    }

    public String artifactId() {
        return artifactId;
    }

    public String coordinate() {
        return coordinate;
    }

    public List<Path> pathsChecked() {
        return pathsChecked;
    }

    public String jarProperty() {
        return jarProperty;
    }
}
