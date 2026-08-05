// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.JkBuildEditor;
import cc.jumpkick.model.Scope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Engine-hosted {@code jk.toml} edits via {@link JkBuildEditor} (client never parses TOML). Ops:
 * add/remove dependency, add-file-dependency, add/register/remove workspace module.
 */
public final class EditOps {

    /** {@code changed} false = the edit was a no-op (content already as requested). */
    public record Result(boolean changed, String error) {}

    private EditOps() {}

    public static Result apply(Path file, String op, List<String> args) {
        try {
            String original = Files.readString(file, StandardCharsets.UTF_8);
            String updated =
                    switch (op) {
                        case "add-dependency" ->
                            JkBuildEditor.addDependency(
                                    original,
                                    Scope.fromCanonical(args.get(0)),
                                    args.get(1),
                                    args.get(2),
                                    args.get(3),
                                    args.get(4));
                        case "add-file-dependency" ->
                            JkBuildEditor.addFileDependency(
                                    original,
                                    Scope.fromCanonical(args.get(0)),
                                    args.get(1),
                                    args.get(2),
                                    args.get(3),
                                    args.get(4),
                                    args.get(5));
                        case "remove-dependency" ->
                            JkBuildEditor.removeDependency(original, Scope.fromCanonical(args.get(0)), args.get(1));
                        case "add-workspace-module" -> JkBuildEditor.addWorkspaceModule(original, args.get(0));
                        case "register-workspace-module" ->
                            JkBuildEditor.registerWorkspaceModule(original, args.get(0));
                        case "remove-workspace-module" ->
                            JkBuildEditor.removeWorkspaceModule(original, args.get(0));
                        default -> throw new IllegalArgumentException("unknown edit op: " + op);
                    };
            if (updated.equals(original)) return new Result(false, null);
            Files.writeString(file, updated, StandardCharsets.UTF_8);
            return new Result(true, null);
        } catch (IOException | RuntimeException e) {
            return new Result(false, String.valueOf(e.getMessage()));
        }
    }
}
