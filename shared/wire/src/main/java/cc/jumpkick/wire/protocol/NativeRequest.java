// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** A native-image build request. */
public record NativeRequest(
        @Nullable String dir,
        @Nullable String cache,
        @Nullable String jdksDir,
        @Nullable String mainClass,
        boolean skipTests,
        boolean offline,
        boolean force,
        boolean verbose,
        List<String> extraArgs,
        Map<String, String> graalHomes,
        List<String> moduleDirs) {

    public NativeRequest {
        extraArgs = extraArgs == null ? List.of() : List.copyOf(extraArgs);
        graalHomes = graalHomes == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(graalHomes));
        moduleDirs = moduleDirs == null ? List.of() : List.copyOf(moduleDirs);
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.NATIVE_REQUEST)
                .string("dir", dir)
                .string("cache", cache)
                .string(ProtoJobs.JDKS_DIR, jdksDir)
                .string("mainClass", mainClass)
                .bool("skipTests", skipTests)
                .bool("offline", offline)
                .bool("force", force)
                .bool("verbose", verbose)
                .array("extraArgs", extraArgs)
                .map("graalHomes", graalHomes)
                .array("moduleDirs", moduleDirs)
                .finish();
    }

    public static NativeRequest decode(String json) {
        return new NativeRequest(
                Jsonl.str(json, "dir"),
                Jsonl.str(json, "cache"),
                Jsonl.str(json, ProtoJobs.JDKS_DIR),
                Jsonl.str(json, "mainClass"),
                Jsonl.bool(json, "skipTests", false),
                Jsonl.bool(json, "offline", false),
                Jsonl.bool(json, "force", false),
                Jsonl.bool(json, "verbose", false),
                Jsonl.strArray(json, "extraArgs"),
                Jsonl.strMap(json, "graalHomes"),
                Jsonl.strArray(json, "moduleDirs"));
    }
}
