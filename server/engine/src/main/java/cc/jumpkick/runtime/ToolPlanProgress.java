// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.compat.ToolProgress;
import cc.jumpkick.jdk.JdkProgressLabel;
import cc.jumpkick.run.TaskContext;
import java.util.concurrent.CancellationException;

/**
 * A tool install's progress on a plan step: the {@code ensure-jdk} labels ({@link JdkProgressLabel}),
 * so a terminal draws the same bar and percentage for Node.js and its package managers as for a JDK,
 * one label per percent. A cancelled step stops the download at its next chunk.
 */
public final class ToolPlanProgress implements ToolProgress {

    private final TaskContext ctx;
    private volatile int lastPct = Integer.MIN_VALUE;
    private volatile String lastName = "";

    public ToolPlanProgress(TaskContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void downloading(String name, long readBytes, long totalBytes) {
        if (ctx.cancelled()) throw new CancellationException(name + " download cancelled");
        int pct = JdkProgressLabel.percent(readBytes, totalBytes);
        if (pct == lastPct && name.equals(lastName)) return;
        lastPct = pct;
        lastName = name;
        ctx.label(JdkProgressLabel.downloading(name, readBytes, totalBytes));
    }

    @Override
    public void installing(String name) {
        ctx.label(JdkProgressLabel.installing(name));
    }
}
