// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * A worker process whose wait returns only once what jk does at its exit is done: the memory lease
 * released and the heap it was seen to use recorded. A caller that waits for a worker and then
 * reads the learned heaps, or deletes the directory they live in, sees the worker's last write.
 * Everything else is the process itself.
 */
final class SettledProcess extends Process {

    private final Process process;
    private final CompletableFuture<?> settled;

    /** {@code process}, whose waits also wait for {@code settled}, the work its exit starts. */
    SettledProcess(Process process, CompletableFuture<?> settled) {
        this.process = process;
        this.settled = settled.handle((ok, failed) -> null);
    }

    @Override
    public int waitFor() throws InterruptedException {
        int code = process.waitFor();
        try {
            settled.get();
        } catch (ExecutionException never) {
            // handle() above completes normally
        }
        return code;
    }

    /** The process's own bounded wait, then the exit work's, each given the whole timeout. */
    @Override
    public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
        if (!process.waitFor(timeout, unit)) return false;
        try {
            settled.get(timeout, unit);
            return true;
        } catch (TimeoutException | ExecutionException late) {
            return false;
        }
    }

    @Override
    public CompletableFuture<Process> onExit() {
        return settled.thenApply(done -> this);
    }

    @Override
    public OutputStream getOutputStream() {
        return process.getOutputStream();
    }

    @Override
    public InputStream getInputStream() {
        return process.getInputStream();
    }

    @Override
    public InputStream getErrorStream() {
        return process.getErrorStream();
    }

    @Override
    public int exitValue() {
        return process.exitValue();
    }

    @Override
    public void destroy() {
        process.destroy();
    }

    @Override
    public Process destroyForcibly() {
        process.destroyForcibly();
        return this;
    }

    @Override
    public boolean supportsNormalTermination() {
        return process.supportsNormalTermination();
    }

    @Override
    public boolean isAlive() {
        return process.isAlive();
    }

    @Override
    public long pid() {
        return process.pid();
    }

    @Override
    public ProcessHandle toHandle() {
        return process.toHandle();
    }

    @Override
    public ProcessHandle.Info info() {
        return process.info();
    }

    @Override
    public Stream<ProcessHandle> children() {
        return process.children();
    }

    @Override
    public Stream<ProcessHandle> descendants() {
        return process.descendants();
    }
}
