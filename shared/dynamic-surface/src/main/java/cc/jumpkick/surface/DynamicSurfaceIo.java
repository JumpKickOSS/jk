// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.surface;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Serialize / deserialize {@link DynamicSurface} as JSON, and import Graal tracing-agent output
 * directories into a surface.
 */
public final class DynamicSurfaceIo {

    private DynamicSurfaceIo() {}

    /** Compact, stable JSON for {@code dynamic-surface.json}. */
    public static String toJson(DynamicSurface surface) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"version\": 1,\n  \"entries\": [\n");
        List<DynamicSurface.Entry> entries = surface.entries();
        for (int i = 0; i < entries.size(); i++) {
            DynamicSurface.Entry e = entries.get(i);
            sb.append("    {\"kind\":")
                    .append(quote(e.kind().name()))
                    .append(",\"name\":")
                    .append(quote(e.name()))
                    .append(",\"origin\":")
                    .append(quote(e.origin()));
            if (!e.members().isEmpty()) {
                sb.append(",\"members\":[");
                boolean first = true;
                for (String m : e.members()) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append(quote(m));
                }
                sb.append(']');
            }
            sb.append('}');
            if (i + 1 < entries.size()) sb.append(',');
            sb.append('\n');
        }
        sb.append("  ]\n}\n");
        return sb.toString();
    }

    /** Parse {@link #toJson} output (and tolerate extra whitespace). */
    @SuppressWarnings("unchecked")
    public static DynamicSurface fromJson(String json) {
        Object root = Json.parse(json);
        if (!(root instanceof Map<?, ?> map)) return DynamicSurface.empty();
        Object entries = map.get("entries");
        if (!(entries instanceof List<?> list)) return DynamicSurface.empty();
        List<DynamicSurface.Entry> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            String kindName = str(m.get("kind"));
            String name = str(m.get("name"));
            if (kindName.isBlank() || name.isBlank()) continue;
            DynamicSurface.Kind kind;
            try {
                kind = DynamicSurface.Kind.valueOf(kindName);
            } catch (IllegalArgumentException e) {
                continue;
            }
            Set<String> members = new TreeSet<>();
            Object mem = m.get("members");
            if (mem instanceof List<?> ml) {
                for (Object o : ml) {
                    if (o != null) members.add(String.valueOf(o));
                }
            }
            out.add(new DynamicSurface.Entry(kind, name, members, str(m.get("origin"))));
        }
        return new DynamicSurface(out);
    }

    public static void writeJson(Path file, DynamicSurface surface) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, toJson(surface), StandardCharsets.UTF_8);
    }

    public static DynamicSurface readJson(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return DynamicSurface.empty();
        return fromJson(Files.readString(file, StandardCharsets.UTF_8));
    }

    /**
     * Import a Graal tracing-agent {@code config-output-dir} (reflect-config.json, etc.) as a
     * surface with origin {@code train:<profile>}.
     */
    public static DynamicSurface importAgentDir(Path agentDir, String origin) throws IOException {
        if (!Files.isDirectory(agentDir)) return DynamicSurface.empty();
        DynamicSurface surface = DynamicSurface.empty();
        try (var stream = Files.list(agentDir)) {
            for (Path file : stream.filter(Files::isRegularFile).sorted().toList()) {
                String name = file.getFileName().toString();
                if (!NativeImageMetadata.CONFIG_FILES.contains(name)) continue;
                String body = Files.readString(file, StandardCharsets.UTF_8);
                surface = surface.merge(NativeImageMetadata.parse(name, body, origin));
            }
        }
        // Nested META-INF/native-image trees some agents write
        Path meta = agentDir.resolve("META-INF/native-image");
        if (Files.isDirectory(meta)) {
            try (var walk = Files.walk(meta)) {
                for (Path file : walk.filter(Files::isRegularFile).toList()) {
                    String rel = meta.relativize(file).toString().replace('\\', '/');
                    String entryName = "META-INF/native-image/" + rel;
                    if (!NativeImageMetadata.isMetadataFile(entryName)) continue;
                    surface = surface.merge(NativeImageMetadata.parse(
                            entryName, Files.readString(file, StandardCharsets.UTF_8), origin));
                }
            }
        }
        return surface;
    }

    /**
     * Write Graal unified reachability metadata under {@code dir} as {@code
     * reachability-metadata.json} (directory form for {@code -H:ConfigurationFileDirectories}).
     */
    public static void writeReachabilityDir(Path dir, DynamicSurface surface) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(
                dir.resolve("reachability-metadata.json"),
                ReachabilityMetadataEmitter.emit(surface),
                StandardCharsets.UTF_8);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String quote(String raw) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
