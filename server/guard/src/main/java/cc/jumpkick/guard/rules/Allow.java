// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.rules;

/**
 * One exemption: a place the rule does not apply and why. The reason is required at load — an
 * exemption without a reason is a suppression, and there is no suppression syntax.
 *
 * @param in a module, package, class, member, file glob or fingerprint prefix, as the kind reads it;
 *     a member is {@code pkg.Class#name} (a {@code forbid} rule's method, which covers the lambdas in
 *     its body, or a {@code statics} rule's field); a file is spelled from the workspace root through
 *     the source root that holds it on disk ({@code m/src/main/kotlin/…}, a compact module's {@code
 *     m/src/…}), and under {@code src/main/java} only when no root holds it
 */
public record Allow(String in, String reason) {}
