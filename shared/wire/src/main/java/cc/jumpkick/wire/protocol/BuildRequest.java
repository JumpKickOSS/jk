// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.jsonl.Jsonl;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** A workspace build request. Optional selection and workspace fields are omitted at their defaults. */
public record BuildRequest(
        @Nullable String dir,
        @Nullable String cache,
        @Nullable String jdksDir,
        int workers,
        @Nullable String profile,
        boolean skipTests,
        boolean verbose,
        int maxModuleConcurrency,
        boolean parallelTests,
        boolean offline,
        boolean force,
        boolean freshenLock,
        boolean ephemeralActions,
        boolean testOnly,
        @Nullable List<String> dirtyHint,
        @Nullable TestSelection selection,
        @Nullable List<String> modules,
        boolean keepGoing,
        @Nullable String workspaceTarget,
        @Nullable Map<String, String> graalHomes,
        /** Who started the build ({@code cli}, {@code web}, {@code ci}, …); the requester's answer, journaled as such. */
        @Nullable String trigger,
        /** Progress-bar mode the requester's environment asked for; null for auto. */
        @Nullable String progressMode) {

    public BuildRequest {
        dirtyHint = dirtyHint == null || dirtyHint.isEmpty() ? null : List.copyOf(dirtyHint);
        selection = selection == null ? TestSelection.DEFAULT : selection;
        modules = modules == null ? List.of() : List.copyOf(modules);
        workspaceTarget = workspaceTarget == null || workspaceTarget.isBlank() ? null : workspaceTarget;
        graalHomes = graalHomes == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(graalHomes));
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.BUILD_REQUEST)
                .string("dir", dir)
                .string("cache", cache)
                .string(ProtoJobs.JDKS_DIR, jdksDir)
                .number("workers", workers)
                .string("profile", profile)
                .bool("skipTests", skipTests)
                .bool("verbose", verbose)
                .number("maxModuleConcurrency", maxModuleConcurrency)
                .bool("parallelTests", parallelTests)
                .bool("offline", offline)
                .bool("force", force)
                .bool("freshenLock", freshenLock)
                .optionalTrue("ephemeralActions", ephemeralActions)
                .optionalTrue("testOnly", testOnly)
                .optionalArray("dirtyHint", dirtyHint)
                .testSelection(selection, true)
                .optionalArray("modules", modules)
                .optionalTrue("keepGoing", keepGoing)
                .optionalNonBlankString("workspaceTarget", workspaceTarget)
                .optionalMap("graalHomes", graalHomes)
                .optionalNonBlankString("trigger", trigger)
                .optionalNonBlankString("progressMode", progressMode)
                .finish();
    }

    public static BuildRequest decode(String json) {
        List<String> dirty = Jsonl.strArray(json, "dirtyHint");
        return new BuildRequest(
                Jsonl.str(json, "dir"),
                Jsonl.str(json, "cache"),
                Jsonl.str(json, ProtoJobs.JDKS_DIR),
                Jsonl.intValue(json, "workers", 0),
                Jsonl.str(json, "profile"),
                Jsonl.bool(json, "skipTests", false),
                Jsonl.bool(json, "verbose", false),
                Jsonl.intValue(json, "maxModuleConcurrency", 0),
                Jsonl.bool(json, "parallelTests", true),
                Jsonl.bool(json, "offline", false),
                Jsonl.bool(json, "force", false),
                Jsonl.bool(json, "freshenLock", false),
                Jsonl.bool(json, "ephemeralActions", false),
                Jsonl.bool(json, "testOnly", false),
                dirty.isEmpty() ? null : dirty,
                ProtoJobs.testSelectionOf(json),
                Jsonl.strArray(json, "modules"),
                Jsonl.bool(json, "keepGoing", false),
                Jsonl.str(json, "workspaceTarget"),
                Jsonl.strMap(json, "graalHomes"),
                Jsonl.str(json, "trigger"),
                Jsonl.str(json, "progressMode"));
    }
}
