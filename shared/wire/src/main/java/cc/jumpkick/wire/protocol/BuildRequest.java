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
        TestSelection selection,
        /** {@link cc.jumpkick.config.DebugJvm#spelling() Spelling} of the JDWP listener for the JVM under test; null for none. */
        @Nullable String debugJvm,
        List<String> modules,
        boolean keepGoing,
        @Nullable String workspaceTarget,
        Map<String, String> graalHomes,
        /**
         * Install / reshelve: the client-resolved local Maven repo root ({@code --m2-dir}). Rides
         * the wire for the same reason {@code graalHomes} does — the daemon does not inherit the
         * caller's environment, so a spec rebuilt without this falls back to the <em>engine's</em>
         * {@code ~/.m2} and a redirected install writes the real one.
         */
        @Nullable String m2Dir,
        /** Who started the build ({@code cli}, {@code web}, {@code ci}, …); the requester's answer, journaled as such. */
        @Nullable String trigger,
        /** Progress-bar mode the requester's environment asked for; null for auto. */
        @Nullable String progressMode,
        /** {@code --coverage}: suite JVMs under the JaCoCo agent, a report per module. */
        boolean coverage) {

    public BuildRequest {
        dirtyHint = dirtyHint == null || dirtyHint.isEmpty() ? null : List.copyOf(dirtyHint);
        selection = selection == null ? TestSelection.DEFAULT : selection;
        modules = modules == null ? List.of() : List.copyOf(modules);
        workspaceTarget = workspaceTarget == null || workspaceTarget.isBlank() ? null : workspaceTarget;
        graalHomes = graalHomes == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(graalHomes));
    }

    /** No coverage. */
    public BuildRequest(
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
            TestSelection selection,
            @Nullable String debugJvm,
            List<String> modules,
            boolean keepGoing,
            @Nullable String workspaceTarget,
            Map<String, String> graalHomes,
            @Nullable String m2Dir,
            @Nullable String trigger,
            @Nullable String progressMode) {
        this(
                dir,
                cache,
                jdksDir,
                workers,
                profile,
                skipTests,
                verbose,
                maxModuleConcurrency,
                parallelTests,
                offline,
                force,
                freshenLock,
                ephemeralActions,
                testOnly,
                dirtyHint,
                selection,
                debugJvm,
                modules,
                keepGoing,
                workspaceTarget,
                graalHomes,
                m2Dir,
                trigger,
                progressMode,
                false);
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
                .optionalNonBlankString(ProtoJobs.DEBUG_JVM, debugJvm)
                .optionalArray("modules", modules)
                .optionalTrue("keepGoing", keepGoing)
                .optionalNonBlankString("workspaceTarget", workspaceTarget)
                .optionalMap("graalHomes", graalHomes)
                .optionalNonBlankString("m2Dir", m2Dir)
                .optionalNonBlankString("trigger", trigger)
                .optionalNonBlankString("progressMode", progressMode)
                .optionalTrue(ProtoJobs.COVERAGE, coverage)
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
                Jsonl.str(json, ProtoJobs.DEBUG_JVM),
                Jsonl.strArray(json, "modules"),
                Jsonl.bool(json, "keepGoing", false),
                Jsonl.str(json, "workspaceTarget"),
                Jsonl.strMap(json, "graalHomes"),
                Jsonl.str(json, "m2Dir"),
                Jsonl.str(json, "trigger"),
                Jsonl.str(json, "progressMode"),
                Jsonl.bool(json, ProtoJobs.COVERAGE, false));
    }
}
