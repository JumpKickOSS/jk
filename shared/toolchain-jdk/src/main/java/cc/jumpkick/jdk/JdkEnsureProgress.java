// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import cc.jumpkick.run.TaskContext;
import java.util.Objects;

/**
 * Live {@code ensure-jdk} detail on a plan step: {@code downloading Temurin 25 ▰…▱ 50%} then
 * {@code installing … 100%} ({@link JdkProgressLabel}). The one label source for a JDK install that
 * runs inside a plan — the engine's {@code ensure-jdk} step and the client's pre-flight alike — so
 * a JSONL consumer sees the same {@code label} lines whichever side did the download.
 * Percent-throttled so the wire coalescer is not flooded.
 */
public final class JdkEnsureProgress implements JdkInstallListener {
    private final TaskContext ctx;
    private volatile String name = "JDK";
    private volatile int lastPct = Integer.MIN_VALUE;

    public JdkEnsureProgress(TaskContext ctx) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
    }

    @Override
    public void onDownloadStart(String label, long totalBytes) {
        if (label != null && !label.isBlank()) name = label;
        lastPct = Integer.MIN_VALUE;
        emitDownload(0, totalBytes);
    }

    @Override
    public void onDownloadProgress(long readBytes, long totalBytes) {
        emitDownload(readBytes, totalBytes);
    }

    @Override
    public void onExtractStart(String label) {
        if (label != null && !label.isBlank()) name = label;
        ctx.label(JdkProgressLabel.installing(name));
    }

    private void emitDownload(long read, long total) {
        if (total <= 0) {
            if (lastPct == -1) return;
            lastPct = -1;
            ctx.label(JdkProgressLabel.downloading(name, 0, 0));
            return;
        }
        int pct = JdkProgressLabel.percent(read, total);
        if (pct == lastPct) return;
        lastPct = pct;
        ctx.label(JdkProgressLabel.downloading(name, read, total));
    }
}
