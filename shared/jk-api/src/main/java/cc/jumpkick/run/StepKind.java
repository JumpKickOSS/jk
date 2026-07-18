// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

/** Workload kind for scheduler executor selection: main-thread, IO pool, or CPU pool. */
public enum StepKind {
    SYNC,
    IO,
    CPU
}
