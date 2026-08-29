// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.util.List;

/**
 * The engine JVM flag set that must be identical wherever an engine-shaped JVM is launched.
 *
 * <p>JEP 514 refuses to map an AOT cache when the dump-time and runtime property sets differ, so
 * the serving spawn line (EngineSpawn), the AOT trainer line (EngineMain), and the manifest row
 * that documents them consume this one list instead of hand-syncing three copies. A flag added to
 * one line and not the other silently degrades every engine start to a cold boot — AOTMode=auto
 * ignores the mismatch, and the refusal back-off then suppresses retraining for its TTL.
 */
public final class EngineJvmFlags {

    /**
     * Serving-line and trainer-line shared flags: SerialGC with tight heap-return ergonomics (an
     * idle coordinator must snap committed to ~live on its boundary GC), real IPv4 sockets for WSL
     * localhost forwarding, and native access for PosixDetach's setsid(2) downcall.
     */
    public static final List<String> AOT_SENSITIVE = List.of(
            "-XX:+UseSerialGC",
            "-XX:MinHeapFreeRatio=10",
            "-XX:MaxHeapFreeRatio=25",
            "-XX:-ShrinkHeapInSteps",
            PreferIpv4.JVM_FLAG,
            "--enable-native-access=ALL-UNNAMED");

    private EngineJvmFlags() {}
}
