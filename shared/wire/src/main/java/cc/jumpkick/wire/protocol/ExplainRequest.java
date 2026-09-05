// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/**
 * Forecast a build (see {@link EngineProtocol#EXPLAIN_REQUEST}). Beyond the plan itself, the fields
 * carry the plan-affecting {@code jk build} options the engine-side ETA estimate needs; the
 * estimate rides back as an {@link EngineProtocol#ETA} event inside the explain burst. {@code
 * selection} is the client's resolved test selection — an input to every module's {@code run-tests}
 * stamp key, so an explain without it forecasts against keys no build computes; the default
 * selection is omitted from the line.
 */
public record ExplainRequest(
        @Nullable String dir,
        @Nullable String cache,
        int workers,
        boolean skipTests,
        @Nullable String profile,
        @Nullable String jdksDir,
        boolean serial,
        boolean parallelTests,
        boolean verbose,
        boolean rebuild,
        int maxModuleConcurrency,
        TestSelection selection) {

    public ExplainRequest {
        selection = selection == null ? TestSelection.DEFAULT : selection;
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.EXPLAIN_REQUEST)
                .string("dir", dir)
                .string("cache", cache)
                .number("workers", workers)
                .bool("skipTests", skipTests)
                .string("profile", profile)
                .string(ProtoJobs.JDKS_DIR, jdksDir)
                .bool("serial", serial)
                .bool("parallelTests", parallelTests)
                .bool("verbose", verbose)
                .bool("rebuild", rebuild)
                .number("maxModuleConcurrency", maxModuleConcurrency)
                .testSelection(selection, true)
                .finish();
    }

    public static ExplainRequest decode(String json) {
        return new ExplainRequest(
                Jsonl.str(json, "dir"),
                Jsonl.str(json, "cache"),
                Jsonl.intValue(json, "workers", 0),
                Jsonl.bool(json, "skipTests", false),
                Jsonl.str(json, "profile"),
                Jsonl.str(json, ProtoJobs.JDKS_DIR),
                Jsonl.bool(json, "serial", false),
                Jsonl.bool(json, "parallelTests", true),
                Jsonl.bool(json, "verbose", false),
                Jsonl.bool(json, "rebuild", false),
                Jsonl.intValue(json, "maxModuleConcurrency", 0),
                ProtoJobs.testSelectionOf(json));
    }
}
