// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.ActionRecordText;
import java.util.Map;

/**
 * What a record states about the toolchain that made it and the platform its outputs are for, read
 * off the inputs its key hashed: the first {@link JdkToolchain} token among them (a compile's {@code
 * jdk}, a packaged step's {@code jdk:} or {@code graal:}, a suite's {@code test-jdk:}) is its {@code
 * TOOLCHAIN}, and the {@code target:} token a platform-bound step keys (jlink, native-image) is its
 * {@code TARGET}. A record with neither names no target: its outputs run anywhere. Statements, never
 * key material.
 */
public final class RecordToolchain {

    /** The key token a platform-bound step names the platform its outputs are for with. */
    public static final String TARGET_TOKEN = "target:";

    private RecordToolchain() {}

    /**
     * {@code meta} with the toolchain and target the record of {@code key} names: the JDK the step
     * that computed {@code key} ran on when it said ({@link ToolchainPolicy#expect}; a compile's or
     * a suite's key names only the Java level), else the first toolchain token among {@code inputs};
     * {@code meta} itself when there is neither and no target.
     */
    public static ActionRecordText.Meta withPlatform(
            ActionRecordText.Meta meta, String key, Map<String, String> inputs) {
        ToolchainPolicy.Expectation expected = ToolchainPolicy.of(key);
        String toolchain = expected == null ? null : expected.toolchain();
        String target = null;
        for (String value : inputs.values()) {
            for (String token : value.split(";")) {
                int at = token.indexOf(JdkToolchain.PREFIX);
                if (toolchain == null && at >= 0)
                    toolchain = token.substring(at).strip();
                if (target == null && token.startsWith(TARGET_TOKEN)) target = token.substring(TARGET_TOKEN.length());
            }
        }
        if (toolchain == null && target == null) return meta;
        return new ActionRecordText.Meta(
                meta.format(),
                meta.kind(),
                meta.writes(),
                meta.created(),
                meta.producer(),
                meta.host(),
                toolchain,
                target,
                meta.wallMillis(),
                meta.sizes(),
                meta.derived());
    }
}
