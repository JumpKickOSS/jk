// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.release;

/** The git tag a release carries: {@code [release] tag}, a template whose {@code {version}} is the released version. */
public final class ReleaseTag {

    /** The template when {@code [release]} names none. */
    public static final String DEFAULT_TEMPLATE = "v{version}";

    private ReleaseTag() {}

    /** {@code template} with {@code {version}} replaced by {@code version}. */
    public static String render(String template, String version) {
        return template.replace("{version}", version);
    }
}
