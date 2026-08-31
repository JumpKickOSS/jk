// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

/**
 * Stand-in trainer fork for {@link PluginAotQuiesceTest}: it exists only to keep the jar on its
 * classpath open until something kills it. Not on the test classpath by accident — the test hands
 * the trainer command a jar and this class name, and the JVM maps that jar to find it.
 */
public final class SleepForever {

    private SleepForever() {}

    public static void main(String[] args) throws InterruptedException {
        Thread.sleep(10 * 60_000L);
    }
}
