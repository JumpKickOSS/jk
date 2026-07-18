// SPDX-License-Identifier: Apache-2.0
/**
 * Locates, reads, and merges user/project configuration through a shared SPI
 * ({@link cc.jumpkick.config.ConfigSources}, {@link cc.jumpkick.config.TomlValues},
 * {@link cc.jumpkick.config.EnvValues}).
 *
 * <p>Layered settings ({@code [config]}, {@code [forge]}): user-global → project
 * {@code jk.toml} → {@code JK_*} env → CLI flags. Machine-scoped settings
 * ({@code [global]}, {@code [cache]}, {@code [engine]}, {@code [http]},
 * {@code [history]}) ignore project files; only user-global + env apply.
 * Malformed values are "unset", never a hard failure.
 */
package cc.jumpkick.config;
