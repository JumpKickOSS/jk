// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import cc.jumpkick.model.InstallDestination;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Resolved first-party project identity for one workspace member (or the standalone root at
 * {@code path = "."}). Captures concrete values after {@code *.workspace = true}
 * inheritance so a re-lock is the only way those pins change.
 */
public record ModuleEntry(
        String path,
        String group,
        String name,
        String version,
        @Nullable Integer java,
        @Nullable String kotlin,
        @Nullable String groovy,
        @Nullable String scala,
        @Nullable String description,
        @Nullable String sources,
        @Nullable Boolean m2integration,
        @Nullable InstallDestination installDestination) {
    public ModuleEntry {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
    }

    /** Unset Scala pin; {@code installDestination} unset (null). */
    public ModuleEntry(
            String path,
            String group,
            String name,
            String version,
            @Nullable Integer java,
            @Nullable String kotlin,
            @Nullable String groovy,
            @Nullable String description,
            @Nullable String sources,
            @Nullable Boolean m2integration) {
        this(path, group, name, version, java, kotlin, groovy, description, sources, m2integration, null);
    }

    /** Unset Scala pin. */
    public ModuleEntry(
            String path,
            String group,
            String name,
            String version,
            @Nullable Integer java,
            @Nullable String kotlin,
            @Nullable String groovy,
            @Nullable String description,
            @Nullable String sources,
            @Nullable Boolean m2integration,
            @Nullable InstallDestination installDestination) {
        this(
                path,
                group,
                name,
                version,
                java,
                kotlin,
                groovy,
                null,
                description,
                sources,
                m2integration,
                installDestination);
    }
}
