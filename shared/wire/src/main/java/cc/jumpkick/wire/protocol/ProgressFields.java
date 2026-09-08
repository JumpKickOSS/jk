// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

/** The one field order the two progress-shaped events share. */
final class ProgressFields {
    private ProgressFields() {}

    static String encode(
            String type,
            String dir,
            String task,
            int delta,
            long numerator,
            long denominator,
            int tasksTotal,
            int tasksComplete,
            boolean cancelled) {
        return RequestJson.event(type)
                .string("dir", dir)
                .string("task", task)
                .number("delta", delta)
                .number("numerator", numerator)
                .number("denominator", denominator)
                .token("progress", ProtoEvents.progressPercent(numerator, denominator))
                .number("tasksTotal", tasksTotal)
                .number("tasksComplete", tasksComplete)
                .bool("cancelled", cancelled)
                .finish();
    }
}
