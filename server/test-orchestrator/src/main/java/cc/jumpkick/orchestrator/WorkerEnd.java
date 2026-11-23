// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.orchestrator;

/**
 * How a suite-running fork ended, as {@link ResultAggregator#toResult} reads it. The engine knows
 * the process: its exit code, whether the kernel killed it for memory, and the launcher failure to
 * raise for a fork that died before its first test.
 */
public interface WorkerEnd {

    /** The fork's exit code. */
    int code();

    /** The death to name for the run: a memory kill when there was one, else {@code otherwise}. */
    String failure(String otherwise);

    /**
     * The failure to raise for a fork that never reported a test, carrying {@code crashOutput}
     * (what it printed outside the protocol) for {@code moduleLabel}.
     */
    RuntimeException nothingRan(String moduleLabel, String crashOutput);
}
