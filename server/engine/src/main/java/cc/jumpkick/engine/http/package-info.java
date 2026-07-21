// SPDX-License-Identifier: Apache-2.0
/**
 * Optional embedded HTTP server, enabled only when {@code [http]} is present in config
 * ({@link cc.jumpkick.config.JkHttpConfig}). Handlers must be IO-shaped: CPU work goes to {@link
 * cc.jumpkick.run.JkThreads#cpu()} so virtual-thread carriers are not pinned. Bind failure is
 * advisory — the engine still serves builds.
 */
package cc.jumpkick.engine.http;
