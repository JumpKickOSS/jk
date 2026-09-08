// SPDX-License-Identifier: Apache-2.0
/**
 * The verbs that run the build and read what it produced: build, test, compile, assembly, native, image,
 * publish, format, run, watch, install, dev, train, guard, jshell, and the pieces they share (the run view,
 * the tails, the Graal policy, the AOT cache package). They read the root helpers and the CLI's api; no
 * other family is read from here.
 */
@NullMarked
package cc.jumpkick.command.pipeline;

import org.jspecify.annotations.NullMarked;
