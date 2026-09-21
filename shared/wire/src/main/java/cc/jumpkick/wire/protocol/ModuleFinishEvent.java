// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import org.jspecify.annotations.Nullable;

/**
 * A module's terminal. {@code didWork} and {@code cancelled} are additive; the image fields ride
 * only for an image module (each only when set) and are followed by {@code hasImage} so a reader can
 * tell "image module with nothing to say" from "not an image module" (see {@link EngineProtocol#MODULE_FINISH}).
 * The shelf fields ({@code shelfCoord}, {@code shelfJarSha256}, {@code shelfPomSha256}) ride only
 * when an install pass shelved the module, all three together.
 */
public record ModuleFinishEvent(
        String dir,
        String coord,
        boolean success,
        int exitCode,
        long millis,
        boolean didWork,
        boolean cancelled,
        ModuleOutcome.@Nullable Image image,
        ModuleOutcome.@Nullable Shelved shelved) {
    public String encode() {
        RequestJson json = RequestJson.request(EngineProtocol.MODULE_FINISH)
                .string("dir", dir)
                .string("coord", coord)
                .bool("success", success)
                .number("exitCode", exitCode)
                .number("millis", millis)
                .bool("didWork", didWork)
                .bool("cancelled", cancelled);
        if (image != null) {
            json.optionalString("imageRef", image.ref())
                    .optionalString("imageTarball", image.tarball())
                    .optionalString("imageName", image.name())
                    .optionalString("imageVersion", image.version())
                    .optionalString("imageDaemonExe", image.daemonExe())
                    .bool("hasImage", true);
        }
        if (shelved != null) {
            json.string("shelfCoord", shelved.coordinate())
                    .string("shelfJarSha256", shelved.jarSha256())
                    .string("shelfPomSha256", shelved.pomSha256());
        }
        return json.finish();
    }

    public static ModuleFinishEvent decode(String json) {
        ModuleOutcome.Image image = Jsonl.bool(json, "hasImage", false)
                ? new ModuleOutcome.Image(
                        Jsonl.str(json, "imageRef"),
                        Jsonl.str(json, "imageTarball"),
                        Jsonl.str(json, "imageName"),
                        Jsonl.str(json, "imageVersion"),
                        Jsonl.str(json, "imageDaemonExe"))
                : null;
        String shelfCoord = Jsonl.str(json, "shelfCoord");
        ModuleOutcome.Shelved shelved = shelfCoord == null
                ? null
                : new ModuleOutcome.Shelved(
                        shelfCoord,
                        Jsonl.requiredStr(json, "shelfJarSha256"),
                        Jsonl.requiredStr(json, "shelfPomSha256"));
        return new ModuleFinishEvent(
                Jsonl.requiredStr(json, "dir"),
                Jsonl.requiredStr(json, "coord"),
                Jsonl.bool(json, "success", false),
                Jsonl.intValue(json, "exitCode", 0),
                Jsonl.longValue(json, "millis", 0),
                Jsonl.bool(json, "didWork", false),
                Jsonl.bool(json, "cancelled", false),
                image,
                shelved);
    }
}
