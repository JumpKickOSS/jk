// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime;

/**
 * One image a publish run's image lane pushed, or on a dry run would push: the module that built
 * it, the {@code <registry>/<name>:<tag>} reference, and the digest the registry holds it under
 * ({@code ""} on a dry run, and for a Dockerfile push whose runtime printed none).
 */
public record ImagePush(String module, String reference, String digest, boolean dryRun) {}
