// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.tool;

/**
 * Provenance of an installed tool for {@code env.json} ({@code kind}/{@code spec}/{@code resolved}).
 */
public record ToolProvenance(String kind, String spec, String resolved) {}
