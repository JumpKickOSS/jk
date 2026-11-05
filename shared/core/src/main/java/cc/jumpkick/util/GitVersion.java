// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A {@code git} command's version, as its {@code git --version} line names it. */
public record GitVersion(int major, int minor) {

    private static final Pattern LINE = Pattern.compile("^git version (\\d+)\\.(\\d+).*");

    /** The version a {@code git --version} line names, or empty when the text is not one. */
    public static Optional<GitVersion> parse(String versionLine) {
        Matcher m = LINE.matcher(versionLine.strip());
        if (!m.find()) return Optional.empty();
        return Optional.of(new GitVersion(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
    }

    /** Whether this git reads {@code GIT_CONFIG_COUNT} / {@code GIT_CONFIG_KEY_n} / {@code GIT_CONFIG_VALUE_n}: 2.31 on. */
    public boolean readsConfigEnv() {
        return major > 2 || (major == 2 && minor >= 31);
    }

    @Override
    public String toString() {
        return major + "." + minor;
    }
}
