// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

/**
 * An HTTP/MCP job submission for a project whose lock pins a different jk version. The wire path
 * delegates such builds to the pinned engine ({@link EngineDelegate}); the HTTP surface refuses
 * instead of silently building with the wrong version — a browser cannot spawn the pinned engine.
 */
public final class PinnedProjectRefused extends IllegalStateException {

    private final String pinnedVersion;
    private final String engineVersion;

    public PinnedProjectRefused(String pinnedVersion, String engineVersion) {
        super("this build pins jk " + pinnedVersion + " but the engine serving HTTP/MCP is " + engineVersion
                + " — run the project's wrapper (./jk) or `jk self update` from a terminal");
        this.pinnedVersion = pinnedVersion;
        this.engineVersion = engineVersion;
    }

    public String pinnedVersion() {
        return pinnedVersion;
    }

    public String engineVersion() {
        return engineVersion;
    }
}
