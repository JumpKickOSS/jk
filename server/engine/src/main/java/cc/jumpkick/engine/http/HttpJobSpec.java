// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import java.util.List;
import java.util.Locale;

/**
 * HTTP/MCP job: kind plus optional module and test-tag selection. {@link EngineHttpJobs#trigger}
 * is the single admission point.
 */
public record HttpJobSpec(
        String kind,
        String dir,
        List<String> modules,
        List<String> includeTags,
        List<String> excludeTags,
        List<String> suites,
        boolean skipTests) {

    public HttpJobSpec {
        kind = kind == null || kind.isBlank() ? "build" : kind.trim().toLowerCase(Locale.ROOT);
        if ("assembly".equals(kind)) kind = "assemble";
        dir = dir == null ? "" : dir;
        modules = modules == null ? List.of() : List.copyOf(modules);
        includeTags = includeTags == null ? List.of() : List.copyOf(includeTags);
        excludeTags = excludeTags == null ? List.of() : List.copyOf(excludeTags);
        suites = suites == null ? List.of() : List.copyOf(suites);
    }

    public static HttpJobSpec of(String kind, String dir) {
        return new HttpJobSpec(kind, dir, List.of(), List.of(), List.of(), List.of(), false);
    }

    public boolean hasModuleFilter() {
        return !modules.isEmpty();
    }

    public boolean hasTestFilter() {
        return !includeTags.isEmpty() || !excludeTags.isEmpty() || !suites.isEmpty();
    }
}
