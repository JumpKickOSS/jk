// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.bsp;

import cc.jumpkick.config.DebugJvm;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.jsonl.MiniJson;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The optional {@code data} object of a BSP test or run request. A malformed payload reads as
 * absent.
 */
final class BspRequestParams {

    private BspRequestParams() {}

    /**
     * Parse optional {@code data} object on a BSP test request into {@link
     * cc.jumpkick.config.TestSelection}. Missing/empty → DEFAULT.
     */
    static TestSelection parseTestSelectionData(String requestJson) {
        if (requestJson == null || requestJson.isBlank()) {
            return TestSelection.DEFAULT;
        }
        try {
            Object parsed = MiniJson.parse(requestJson);
            if (!(parsed instanceof Map<?, ?> outer)) {
                return TestSelection.DEFAULT;
            }
            // Full request envelope or bare params object — unwrap either.
            Map<?, ?> params = outer.get("params") instanceof Map<?, ?> inner ? inner : outer;
            Map<?, ?> src = params.get("data") instanceof Map<?, ?> d ? d : params;
            boolean all = Boolean.TRUE.equals(src.get("allSuites"));
            return TestSelection.of(
                            stringList(src.get("suites")),
                            all,
                            stringList(src.get("includeTags")),
                            stringList(src.get("excludeTags")))
                    .withClasses(stringList(src.get("classes")));
        } catch (RuntimeException e) {
            return TestSelection.DEFAULT;
        }
    }

    /**
     * {@code selection} for a test of one suite target: that suite, unless the request's data
     * already named suites. A module's own target, or several targets, leave it as it is.
     */
    static TestSelection withTargetSuite(TestSelection selection, List<String> targetUris) {
        if (targetUris.size() != 1 || !selection.suites().isEmpty() || selection.allSuites()) return selection;
        String suite = BspServer.suiteOf(targetUris.getFirst());
        if (suite == null) return selection;
        return TestSelection.of(List.of(suite), false, selection.includeTags(), selection.excludeTags())
                .withClasses(selection.classes());
    }

    /**
     * The {@code data.debug} field of a BSP test/run request as a {@link DebugJvm}, or null when
     * absent or false. Accepted shapes: {@code true} (the defaults), an object with optional
     * {@code host}, {@code port} and {@code suspend}, or the {@code --debug-jvm} spec as a string.
     * Unparseable payloads read as no debug request, like the selection parse beside it.
     */
    static @Nullable DebugJvm parseDebugData(String requestJson) {
        if (requestJson == null || requestJson.isBlank()) return null;
        try {
            Object parsed = MiniJson.parse(requestJson);
            if (!(parsed instanceof Map<?, ?> outer)) return null;
            Map<?, ?> params = outer.get("params") instanceof Map<?, ?> inner ? inner : outer;
            Map<?, ?> src = params.get("data") instanceof Map<?, ?> d ? d : params;
            Object debug = src.get("debug");
            if (debug instanceof Boolean on) return on ? DebugJvm.DEFAULT : null;
            if (debug instanceof String spec) return DebugJvm.parse(spec);
            if (!(debug instanceof Map<?, ?> fields)) return null;
            String host = fields.get("host") instanceof String h && !h.isBlank() ? h : DebugJvm.DEFAULT_HOST;
            int port = fields.get("port") instanceof Number n ? n.intValue() : DebugJvm.DEFAULT_PORT;
            boolean suspend = !Boolean.FALSE.equals(fields.get("suspend"));
            return new DebugJvm(host, port, suspend);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<String> stringList(@Nullable Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o instanceof String str && !str.isBlank()) out.add(str);
        }
        return out;
    }
}
