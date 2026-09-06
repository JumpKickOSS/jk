// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.rules;

/**
 * One exemption: a place the rule does not apply and why. The reason is required at load — an
 * exemption without a reason is a suppression, and there is no suppression syntax.
 *
 * @param in a module, package, class, file glob or fingerprint prefix, as the kind reads it
 */
public record Allow(String in, String reason) {}
