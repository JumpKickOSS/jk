// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import org.jspecify.annotations.Nullable;

/**
 * The {@code [guards]} table of {@code jk.toml}: the two knobs a project has over the guard lanes.
 * The rules themselves live in {@code jk-guards.toml}, never in the manifest — the manifest is the
 * file agents edit most.
 *
 * @param onBuild whether the module and workspace lanes run on every {@code jk build} / {@code jk
 *     test} (default) or only on {@code --gate}
 * @param coverageReport path of the coverage XML the {@code coverage.*} measures read, relative to
 *     the workspace root; {@code null} means the test task's own report
 * @param declared whether the table was present at all — its presence alone enables the guard
 *     lanes, so a project that never wrote it pays nothing
 */
public record GuardsConfig(boolean onBuild, @Nullable String coverageReport, boolean declared) {

    public static final GuardsConfig ABSENT = new GuardsConfig(true, null, false);

    public GuardsConfig {
        if (coverageReport != null && coverageReport.isBlank()) coverageReport = null;
    }
}
