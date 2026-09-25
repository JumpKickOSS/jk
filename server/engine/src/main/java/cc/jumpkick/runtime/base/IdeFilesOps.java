// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.host.Errors;
import cc.jumpkick.ide.BspConnectionFile;
import cc.jumpkick.ide.IdeGeneration;
import cc.jumpkick.ide.IdeGenerators;
import cc.jumpkick.ide.IdeModel;
import cc.jumpkick.ide.IdeTarget;
import cc.jumpkick.wire.protocol.IdeWireModel;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Engine-hosted {@code jk ide}: the {@link IdeOps} model run through the same {@link
 * IdeGenerators} the CLI uses, plus {@code .bsp/jk.json}. What the {@code ide} MCP tool calls.
 * Missing jars are fetched in-line — no hosted sync runs ahead of this path — and the IDE spawns
 * {@code jk} from the PATH.
 */
public final class IdeFilesOps {

    private IdeFilesOps() {}

    /**
     * The outcome: {@code error} is printable and, when set, the rest is empty.
     *
     * @param generations one per IDE generated, in emit order
     * @param bsp the connection file written, or the one a preview would write
     */
    public record Result(
            @Nullable String error,
            String wsRoot,
            String rootName,
            List<IdeGeneration> generations,
            @Nullable Path bsp) {

        public static Result error(String message) {
            return new Result(message, "", "", List.of(), null);
        }
    }

    /**
     * Generate the project files for the IDEs in {@code targets}; {@code preview} lists the files
     * without writing them or registering SDKs. {@code ideConfigDir} overrides the IDE config root
     * the SDK registrar writes under ({@code null}: the host's real IDE config directories).
     */
    public static Result generate(
            Path startDir,
            Path cache,
            @Nullable Path jdksDir,
            @Nullable Path ideConfigDir,
            Set<IdeTarget> targets,
            boolean preview) {
        IdeWireModel wire = IdeOps.ideModel(startDir, cache, jdksDir, true);
        if (wire.error() != null) return Result.error(wire.error());
        try {
            IdeModel model = IdeModel.fromWire(wire, ideConfigDir);
            List<IdeGeneration> generations = IdeGenerators.run(model, targets, preview);
            Path bsp = preview
                    ? model.wsRoot().resolve(".bsp").resolve("jk.json")
                    : BspConnectionFile.write(model.wsRoot(), "jk", BspConnectionFile.languages(wire));
            return new Result(null, wire.wsRoot(), wire.rootName(), generations, bsp);
        } catch (IOException | RuntimeException e) {
            return Result.error(Errors.text(e));
        }
    }
}
