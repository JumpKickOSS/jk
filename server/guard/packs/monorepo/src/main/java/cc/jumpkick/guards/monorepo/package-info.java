// SPDX-License-Identifier: Apache-2.0
/**
 * The monorepo rule pack ({@code cc.jumpkick.guards:monorepo}): a {@code jk-guards.toml} fragment and the
 * fixtures that prove its rules bite, shipped as resources. A project extends it with {@code
 * [guards] extends} in its root rules file; the module exists so both builds package the pack the
 * same way and stage it beside the worker jars.
 */
@NullMarked
package cc.jumpkick.guards.monorepo;

import org.jspecify.annotations.NullMarked;
