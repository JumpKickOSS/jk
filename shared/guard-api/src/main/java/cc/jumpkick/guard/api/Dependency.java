// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api;

/**
 * One declared dependency.
 *
 * @param coordinate {@code group:artifact}, or the sibling module's path for a workspace edge
 * @param version the selector as written ({@code ^1.2}, {@code =1.2.3}, {@code latest}); empty for a workspace edge
 * @param scope the table it is declared in
 * @param workspace a sibling module rather than an artifact
 */
public record Dependency(String coordinate, String version, DepScope scope, boolean workspace) {}
