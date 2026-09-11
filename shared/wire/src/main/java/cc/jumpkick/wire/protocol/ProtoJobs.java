// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.config.DebugJvm;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.runtime.progress.ProgressBarMode;
import org.jspecify.annotations.Nullable;

/** Shared fields used by typed job-request codecs. */
public final class ProtoJobs {

    public static final String JDKS_DIR = "jdksDir";

    /** The {@link DebugJvm#spelling()} a test, build or exec-plan line carries when a JDWP listener was asked for. */
    public static final String DEBUG_JVM = "debugJvm";

    private ProtoJobs() {}

    /** Per-request progress mode, falling back to the engine environment when omitted. */
    public static ProgressBarMode progressModeOf(String json) {
        String raw = Jsonl.str(json, "progressMode");
        return raw == null || raw.isBlank() ? ProgressBarMode.fromEnvironment() : ProgressBarMode.parse(raw);
    }

    public static String testSelectionFields(TestSelection selection) {
        return RequestJson.fields().testSelection(selection, false).suffix();
    }

    public static TestSelection testSelectionOf(String json) {
        return TestSelection.of(
                Jsonl.strArray(json, "suites"),
                Jsonl.bool(json, "allSuites", false),
                Jsonl.strArray(json, "includeTags"),
                Jsonl.strArray(json, "excludeTags"),
                Jsonl.bool(json, "tagsResolved", false),
                Jsonl.bool(json, "guard", false),
                Jsonl.bool(json, "scriptsOnly", false),
                Jsonl.bool(json, "noScripts", false),
                Jsonl.strArray(json, "classes"));
    }

    /** The requested JDWP listener, or {@code null} when the line carries none. */
    public static @Nullable DebugJvm debugJvmOf(String json) {
        return DebugJvm.parseOrNull(Jsonl.str(json, DEBUG_JVM));
    }

    /** {@link DebugJvm#spelling()} for the wire, or {@code null} for no debug request. */
    public static @Nullable String debugJvmSpelling(@Nullable DebugJvm debug) {
        return debug == null ? null : debug.spelling();
    }
}
