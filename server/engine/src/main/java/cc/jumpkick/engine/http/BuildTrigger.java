// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

/**
 * {@code POST /api/build} seam: start a build, return request id immediately; progress on SSE
 * only ({@code 202}).
 */
@FunctionalInterface
public interface BuildTrigger {

    /**
     * Start a build of the workspace/project at {@code dir} (an absolute path containing {@code
     * jk.toml}) and return its request id immediately.
     *
     * @throws IllegalArgumentException when {@code dir} isn't buildable — relayed as a {@code 400}
     */
    long trigger(String dir);
}
