// SPDX-License-Identifier: Apache-2.0
/**
 * Engine validations: invariants of the build model itself, not rules about the code, so they
 * live in no rule file. {@link cc.jumpkick.guard.validate.TierPartition} proves the test-tag tier
 * table partitions its own vocabulary and that every compiled {@code @Tag} is in it;
 * {@link cc.jumpkick.guard.validate.CatalogLockParity} keeps a Gradle version catalog and the lock
 * agreeing where they overlap. They ride the guard lanes — a workspace without guards runs none —
 * and report under reserved codes no user rule may take.
 */
@NullMarked
package cc.jumpkick.guard.validate;

import org.jspecify.annotations.NullMarked;
