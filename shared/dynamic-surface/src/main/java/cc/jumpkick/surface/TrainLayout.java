// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.surface;

import java.nio.file.Path;

/**
 * Canonical paths under {@code target/train/} for train outputs. Stable so consumers and docs
 * share one layout.
 */
public final class TrainLayout {

    public static final String ROOT = "train";
    public static final String FINGERPRINT = "fingerprint";
    public static final String SURFACE_JSON = "dynamic-surface.json";
    public static final String KEEPS_PRO = "keeps.pro";
    public static final String REACHABILITY = "reachability";
    public static final String AOT_CACHE = "app.aot";

    private TrainLayout() {}

    public static Path root(Path moduleTargetDir) {
        return moduleTargetDir.resolve(ROOT);
    }

    public static Path raw(Path moduleTargetDir) {
        return root(moduleTargetDir).resolve("raw");
    }

    public static Path rawProfile(Path moduleTargetDir, String profile) {
        return raw(moduleTargetDir).resolve(profile);
    }

    public static Path agentDir(Path moduleTargetDir, String profile) {
        return rawProfile(moduleTargetDir, profile).resolve("agent");
    }

    public static Path merged(Path moduleTargetDir) {
        return root(moduleTargetDir).resolve("merged");
    }

    public static Path surfaceJson(Path moduleTargetDir) {
        return merged(moduleTargetDir).resolve(SURFACE_JSON);
    }

    public static Path keepsPro(Path moduleTargetDir) {
        return merged(moduleTargetDir).resolve(KEEPS_PRO);
    }

    public static Path reachabilityDir(Path moduleTargetDir) {
        return merged(moduleTargetDir).resolve(REACHABILITY);
    }

    public static Path fingerprint(Path moduleTargetDir) {
        return root(moduleTargetDir).resolve(FINGERPRINT);
    }

    public static Path aotCache(Path moduleTargetDir) {
        return root(moduleTargetDir).resolve(AOT_CACHE);
    }
}
