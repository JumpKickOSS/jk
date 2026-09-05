// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/**
 * Ranked tests for the working tree, or {@code since...HEAD} when {@code affectedSince} is set;
 * {@code modules} is the raw {@code -m} spec and intersects the ranked cone.
 */
public record AffectedTestsRequest(
        @Nullable String dir,
        TestSelection selection,
        @Nullable String affectedSince,
        @Nullable String modules) {

    public AffectedTestsRequest {
        selection = selection == null ? TestSelection.DEFAULT : selection;
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.AFFECTED_TESTS_REQUEST)
                .string("dir", dir)
                .testSelection(selection, false)
                .optionalNonBlankString("affectedSince", affectedSince)
                .optionalNonBlankString("modules", modules)
                .finish();
    }

    public static AffectedTestsRequest decode(String json) {
        return new AffectedTestsRequest(
                Jsonl.str(json, "dir"),
                ProtoJobs.testSelectionOf(json),
                Jsonl.str(json, "affectedSince"),
                Jsonl.str(json, "modules"));
    }
}
