// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.model.InstallDestination;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * A module's terminal. {@code didWork} and {@code cancelled} are additive; {@code stopped} rides
 * only when true, on a module another module's failure stopped ({@code success} is then false); the image fields ride
 * only for an image module (each only when set) and are followed by {@code hasImage} so a reader can
 * tell "image module with nothing to say" from "not an image module" (see {@link EngineProtocol#MODULE_FINISH}).
 * The install fields ({@code installCoord}, {@code installJarSha256}, {@code installPomSha256}, {@code
 * installDestination}, {@code installChange}) ride only when an install pass installed the module, all together; the {@code published} object only
 * when a publish pass ran.
 */
public record ModuleFinishEvent(
        String dir,
        String coord,
        boolean success,
        int exitCode,
        long millis,
        boolean didWork,
        boolean cancelled,
        boolean stopped,
        ModuleOutcome.@Nullable Image image,
        ModuleOutcome.@Nullable Installed installed,
        ModuleOutcome.@Nullable Published published) {
    public String encode() {
        RequestJson json = RequestJson.request(EngineProtocol.MODULE_FINISH)
                .string("dir", dir)
                .string("coord", coord)
                .bool("success", success)
                .number("exitCode", exitCode)
                .number("millis", millis)
                .bool("didWork", didWork)
                .bool("cancelled", cancelled)
                .optionalTrue("stopped", stopped);
        if (image != null) {
            json.optionalString("imageRef", image.ref())
                    .optionalString("imageTarball", image.tarball())
                    .optionalString("imageName", image.name())
                    .optionalString("imageVersion", image.version())
                    .optionalString("imageDaemonExe", image.daemonExe())
                    .bool("hasImage", true);
        }
        if (installed != null) {
            json.string("installCoord", installed.coordinate())
                    .string("installJarSha256", installed.jarSha256())
                    .string("installPomSha256", installed.pomSha256())
                    .string("installDestination", installed.destination().spelling())
                    .string("installChange", installed.change().word());
        }
        if (published != null) {
            json.token(
                    "published",
                    "{"
                            + RequestJson.fields()
                                    .string("destination", published.destination())
                                    .optionalTrue("dryRun", published.dryRun())
                                    .number("files", published.files())
                                    .number("existing", published.existing())
                                    .optionalArray("written", published.written())
                                    .optionalArray("bundle", published.bundle())
                                    .optionalString("deploymentId", published.deploymentId())
                                    .optionalString("deploymentState", published.deploymentState())
                                    .optionalArray("deploymentErrors", published.deploymentErrors())
                                    .body()
                            + "}");
        }
        return json.finish();
    }

    /** The outcome this event reports for the module at {@code dir}. */
    public ModuleOutcome outcome(Path moduleDir) {
        return new ModuleOutcome(
                coord, moduleDir, success, exitCode, millis, didWork, cancelled, stopped, image, installed, published);
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
        String installCoord = Jsonl.str(json, "installCoord");
        ModuleOutcome.Installed installed = installCoord == null
                ? null
                : new ModuleOutcome.Installed(
                        installCoord,
                        Jsonl.requiredStr(json, "installJarSha256"),
                        Jsonl.requiredStr(json, "installPomSha256"),
                        InstallDestination.parse(Jsonl.requiredStr(json, "installDestination"))
                                .orElseThrow(() -> new IllegalArgumentException("unknown install destination")),
                        ModuleOutcome.Installed.Change.parse(Jsonl.str(json, "installChange")));
        String pub = Jsonl.nested(json, "published");
        ModuleOutcome.Published published = pub == null
                ? null
                : new ModuleOutcome.Published(
                        Jsonl.requiredStr(pub, "destination"),
                        Jsonl.bool(pub, "dryRun", false),
                        Jsonl.intValue(pub, "files", 0),
                        Jsonl.intValue(pub, "existing", 0),
                        Jsonl.strArray(pub, "written"),
                        Jsonl.strArray(pub, "bundle"),
                        Jsonl.str(pub, "deploymentId"),
                        Jsonl.str(pub, "deploymentState"),
                        Jsonl.strArray(pub, "deploymentErrors"));
        return new ModuleFinishEvent(
                Jsonl.requiredStr(json, "dir"),
                Jsonl.requiredStr(json, "coord"),
                Jsonl.bool(json, "success", false),
                Jsonl.intValue(json, "exitCode", 0),
                Jsonl.longValue(json, "millis", 0),
                Jsonl.bool(json, "didWork", false),
                Jsonl.bool(json, "cancelled", false),
                Jsonl.bool(json, "stopped", false),
                image,
                installed,
                published);
    }
}
