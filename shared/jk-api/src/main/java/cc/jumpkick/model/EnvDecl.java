// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

/**
 * One entry of an environment array — {@code [test] env} or {@code [env] vars}: a variable to
 * forward from the caller, or a value to set outright.
 */
public sealed interface EnvDecl {

    /** The variable this entry is about. */
    String name();

    /**
     * A bare name in the array: {@code "JK_WEB_JS_SKIP"}. Take the caller's value if there is
     * one; if there is not, the test JVM does not get the variable at all.
     *
     * <p>Absent, never empty. A suite asking {@code getenv("X") != null} must see what it would
     * see outside jk, so an unset forward cannot become {@code X=""}.
     */
    record Forward(String name) implements EnvDecl {}

    /**
     * A table entry in the array: {@code { TZ = "UTC" }}. The value is what the module says it
     * is, and may reference {@code ${target}}, {@code ${module}} or an environment variable —
     * an unset {@code ${VAR}} here is an error, because a value stated outright and then
     * silently emptied is how a build authenticates anonymously and calls it success.
     */
    record Set(String name, String value) implements EnvDecl {}
}
