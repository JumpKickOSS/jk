// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import java.util.List;
import java.util.Locale;

/**
 * One HTTP/MCP job submission: kind plus optional module and test-tag selection. Decoded into a
 * wire request line by the owning verb ({@code HostedVerb.decodeJob}); {@code EngineHttpJobs}
 * is the single admission point.
 */
public record JobSpec(
        String kind,
        String dir,
        List<String> modules,
        List<String> includeTags,
        List<String> excludeTags,
        List<String> suites,
        boolean skipTests,
        boolean affected) {

    public JobSpec {
        kind = kind == null || kind.isBlank() ? "build" : kind.trim().toLowerCase(Locale.ROOT);
        if ("assembly".equals(kind)) kind = "assemble";
        dir = dir == null ? "" : dir;
        modules = modules == null ? List.of() : List.copyOf(modules);
        includeTags = includeTags == null ? List.of() : List.copyOf(includeTags);
        excludeTags = excludeTags == null ? List.of() : List.copyOf(excludeTags);
        suites = suites == null ? List.of() : List.copyOf(suites);
    }

    public static JobSpec of(String kind, String dir) {
        return new JobSpec(kind, dir, List.of(), List.of(), List.of(), List.of(), false, false);
    }

    /** Same spec with a normalized absolute {@code dir} (the admission point resolves it once). */
    public JobSpec withDir(String absoluteDir) {
        return new JobSpec(kind, absoluteDir, modules, includeTags, excludeTags, suites, skipTests, affected);
    }

    public boolean hasModuleFilter() {
        return !modules.isEmpty();
    }

    public boolean hasTestFilter() {
        return !includeTags.isEmpty() || !excludeTags.isEmpty() || !suites.isEmpty();
    }
}
