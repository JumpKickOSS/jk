// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.model.InstallDestination;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * A module's terminal. {@code waitedMs} rides only when some of {@code millis} was spent blocked;
 * {@code didWork} and {@code cancelled} are additive; {@code stopped} rides
 * only when true, on a module another module's failure stopped ({@code success} is then false); the image fields ride
 * only for an image module (each only when set) and are followed by {@code hasImage} so a reader can
 * tell "image module with nothing to say" from "not an image module" (see {@link EngineProtocol#MODULE_FINISH}).
 * The install fields ({@code installCoord}, {@code installJarSha256}, {@code installPomSha256}, {@code
 * installDestination}, {@code installChange}) ride only when an install pass installed the module, all together; the {@code published} object only
 * when a publish pass ran; the {@code tests} object ({@code total}, {@code skipped}) only when the module ran a suite or
 * replayed one that kept its counts, with {@code "replayed":true} on a replay.
 */
public record ModuleFinishEvent(
        String dir,
        String coord,
        boolean success,
        int exitCode,
        long millis,
        long waitedMillis,
        boolean didWork,
        boolean cancelled,
        boolean stopped,
        ModuleOutcome.@Nullable Image image,
        ModuleOutcome.@Nullable Installed installed,
        ModuleOutcome.@Nullable Published published,
        ModuleOutcome.@Nullable Tests tests) {
    /** The terminal a finished module's outcome is sent as. */
    public static ModuleFinishEvent of(ModuleOutcome o) {
        return new ModuleFinishEvent(
                o.dir().toString(),
                o.coord(),
                o.success(),
                o.exitCode(),
                o.millis(),
                o.waitedMillis(),
                o.didWork(),
                o.cancelled(),
                o.stopped(),
                o.image(),
                o.installed(),
                o.published(),
                o.tests());
    }

    public String encode() {
        RequestJson json = RequestJson.request(EngineProtocol.MODULE_FINISH)
                .string("dir", dir)
                .string("coord", coord)
                .bool("success", success)
                .number("exitCode", exitCode)
                .number("millis", millis)
                .optionalNumber("waitedMs", waitedMillis, 0)
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
        if (tests != null) {
            json.token(
                    "tests",
                    "{"
                            + RequestJson.fields()
                                    .number("total", tests.total())
                                    .number("skipped", tests.skipped())
                                    .optionalTrue("replayed", tests.replayed())
                                    .body()
                            + "}");
        }
        return json.finish();
    }

    /** The outcome this event reports for the module at {@code dir}. */
    public ModuleOutcome outcome(Path moduleDir) {
        return new ModuleOutcome(
                coord,
                moduleDir,
                success,
                exitCode,
                millis,
                waitedMillis,
                didWork,
                cancelled,
                stopped,
                image,
                installed,
                published,
                tests);
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
        String counted = Jsonl.nested(json, "tests");
        ModuleOutcome.Tests tests = counted == null
                ? null
                : new ModuleOutcome.Tests(
                        Jsonl.longValue(counted, "total", 0),
                        Jsonl.longValue(counted, "skipped", 0),
                        Jsonl.bool(counted, "replayed", false));
        return new ModuleFinishEvent(
                Jsonl.requiredStr(json, "dir"),
                Jsonl.requiredStr(json, "coord"),
                Jsonl.bool(json, "success", false),
                Jsonl.intValue(json, "exitCode", 0),
                Jsonl.longValue(json, "millis", 0),
                Jsonl.longValue(json, "waitedMs", 0),
                Jsonl.bool(json, "didWork", false),
                Jsonl.bool(json, "cancelled", false),
                Jsonl.bool(json, "stopped", false),
                image,
                installed,
                published,
                tests);
    }
}
