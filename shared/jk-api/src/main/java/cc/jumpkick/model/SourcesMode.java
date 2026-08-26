// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

/** When a sources JAR is produced ({@code sources}): never / publish only / always. */
public enum SourcesMode {
    DISABLED,
    /** Assembled during {@code jk publish} only. */
    PUBLISH,
    /** Built by {@code jk build} and uploaded by {@code jk publish}. */
    ALWAYS;

    public boolean publishSources() {
        return this != DISABLED;
    }
}
