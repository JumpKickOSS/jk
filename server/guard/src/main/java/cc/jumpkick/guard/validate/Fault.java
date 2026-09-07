// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.validate;

/** One engine-validation failure: the code it reports under, what was observed, what to do. */
public record Fault(String code, String observed, String instead) {}
