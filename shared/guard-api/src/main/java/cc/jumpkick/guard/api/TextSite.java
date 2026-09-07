// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api;

/**
 * A place in a text file. The fingerprint is the path plus the matched text, never the line: a
 * line moves when anything above it changes.
 *
 * @param path workspace-relative
 * @param line 1-based, for the diagnostic
 * @param matched the text that matched, trimmed
 */
public record TextSite(String path, int line, String matched) implements Site {

    @Override
    public String fingerprint() {
        return path + " | " + matched.strip();
    }

    @Override
    public String file() {
        return path;
    }
}
