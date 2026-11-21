// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import cc.jumpkick.jsonl.Jsonl;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * Hand-rolled SLSA v1 in-toto Statement JSON, unsigned: {@code jk publish} wraps it in a DSSE
 * envelope when it signs, and {@code jk image} attaches it to a pushed image as it is.
 */
public final class SlsaProvenance {

    /** The {@code runDetails.builder.id} of every statement jk renders. */
    public static final String BUILDER_ID = "https://github.com/JumpKickOSS/jk";

    /** The {@code buildDefinition.buildType} of every statement jk renders. */
    public static final String BUILD_TYPE = "https://jumpkick.build/jk-build/v1";

    /** A subject is one artifact being attested to. Maven uploads typically attest the main jar. */
    public record Subject(String name, String sha256) {
        public Subject {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(sha256, "sha256");
        }
    }

    /**
     * One artifact the build consumed: its package URL and, when the lock pins one, its SHA-256 —
     * an SLSA {@code ResourceDescriptor} under {@code resolvedDependencies}.
     */
    public record Dependency(String uri, @Nullable String sha256) {
        public Dependency {
            Objects.requireNonNull(uri, "uri");
        }
    }

    /**
     * All parameters the statement encodes. {@link #externalParameters}, {@link #internalParameters}
     * and {@link #resolvedDependencies} may be empty; the dependencies render sorted by uri.
     */
    public record BuildContext(
            String builderId,
            String buildType,
            @Nullable String invocationId,
            @Nullable Instant startedOn,
            @Nullable Instant finishedOn,
            Map<String, String> externalParameters,
            Map<String, String> internalParameters,
            List<Dependency> resolvedDependencies) {

        public BuildContext {
            Objects.requireNonNull(builderId, "builderId");
            Objects.requireNonNull(buildType, "buildType");
            externalParameters = externalParameters == null ? Map.of() : Map.copyOf(externalParameters);
            internalParameters = internalParameters == null ? Map.of() : Map.copyOf(internalParameters);
            List<Dependency> sorted = new ArrayList<>(resolvedDependencies == null ? List.of() : resolvedDependencies);
            sorted.sort(Comparator.comparing(Dependency::uri).thenComparing(d -> d.sha256() == null ? "" : d.sha256()));
            resolvedDependencies = List.copyOf(sorted);
        }
    }

    private SlsaProvenance() {}

    /** Render an in-toto v1 SLSA Provenance statement as UTF-8 JSON bytes. */
    public static byte[] generate(List<Subject> subjects, BuildContext context) {
        Objects.requireNonNull(subjects, "subjects");
        Objects.requireNonNull(context, "context");
        if (subjects.isEmpty()) {
            throw new IllegalArgumentException("at least one subject is required");
        }

        StringBuilder sb = new StringBuilder(512);
        sb.append('{');
        kv(sb, "_type", "https://in-toto.io/Statement/v1");
        sb.append(',');

        sb.append("\"subject\":[");
        for (int i = 0; i < subjects.size(); i++) {
            if (i > 0) sb.append(',');
            Subject s = subjects.get(i);
            sb.append('{');
            kv(sb, "name", s.name());
            sb.append(",\"digest\":{");
            kv(sb, "sha256", s.sha256());
            sb.append('}');
            sb.append('}');
        }
        sb.append(']');
        sb.append(',');

        kv(sb, "predicateType", "https://slsa.dev/provenance/v1");
        sb.append(',');

        sb.append("\"predicate\":{");

        sb.append("\"buildDefinition\":{");
        kv(sb, "buildType", context.buildType());
        sb.append(',');
        sb.append("\"externalParameters\":");
        renderStringMap(sb, context.externalParameters());
        sb.append(',');
        sb.append("\"internalParameters\":");
        renderStringMap(sb, context.internalParameters());
        sb.append(',');
        sb.append("\"resolvedDependencies\":[");
        List<Dependency> deps = context.resolvedDependencies();
        for (int i = 0; i < deps.size(); i++) {
            if (i > 0) sb.append(',');
            Dependency d = deps.get(i);
            sb.append('{');
            kv(sb, "uri", d.uri());
            if (d.sha256() != null) {
                sb.append(",\"digest\":{");
                kv(sb, "sha256", d.sha256());
                sb.append('}');
            }
            sb.append('}');
        }
        sb.append(']');
        sb.append('}');
        sb.append(',');

        sb.append("\"runDetails\":{");
        sb.append("\"builder\":{");
        kv(sb, "id", context.builderId());
        sb.append('}');
        sb.append(',');
        sb.append("\"metadata\":{");
        List<String[]> metadata = new ArrayList<>();
        if (context.invocationId() != null) metadata.add(new String[] {"invocationId", context.invocationId()});
        if (context.startedOn() != null)
            metadata.add(new String[] {"startedOn", context.startedOn().toString()});
        if (context.finishedOn() != null)
            metadata.add(new String[] {"finishedOn", context.finishedOn().toString()});
        for (int i = 0; i < metadata.size(); i++) {
            if (i > 0) sb.append(',');
            kv(sb, metadata.get(i)[0], metadata.get(i)[1]);
        }
        sb.append('}');
        sb.append('}');

        sb.append('}'); // predicate
        sb.append('}'); // statement

        sb.append('\n');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void renderStringMap(StringBuilder sb, Map<String, String> values) {
        sb.append('{');
        // Sorted by key: the context holds a Map.copyOf, whose iteration order changes from one JVM
        // to the next, and a re-run must render the bytes it uploaded the first time.
        Map<String, String> ordered = new TreeMap<>(values);
        int i = 0;
        for (Map.Entry<String, String> e : ordered.entrySet()) {
            if (i++ > 0) sb.append(',');
            kv(sb, e.getKey(), e.getValue());
        }
        sb.append('}');
    }

    private static void kv(StringBuilder sb, String key, String value) {
        appendJsonString(sb, key);
        sb.append(':');
        appendJsonString(sb, value);
    }

    private static void appendJsonString(StringBuilder sb, String s) {
        sb.append(Jsonl.quote(s));
    }
}
