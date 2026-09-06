// SPDX-License-Identifier: Apache-2.0
/**
 * The typed guard tier. A guard test is a JUnit 5 method annotated {@code @Guard(id, why, instead)}
 * in a {@code @GuardSuite} class under {@code src/guard}; it receives the same facts index, build
 * model and text views the engine's declarative rules read, and emits violations through a collector
 * that fingerprints them into the same baseline and report. Published as
 * {@code cc.jumpkick:jk-guards-junit}.
 */
@NullMarked
package cc.jumpkick.guard.api;

import org.jspecify.annotations.NullMarked;
