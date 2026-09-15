// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildEditor;
import cc.jumpkick.guard.eval.MutationCheck;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.library.LibraryCatalog;
import cc.jumpkick.model.Scope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Engine-hosted {@code jk.toml} edits via {@link JkBuildEditor} (client never parses TOML). Ops:
 * add/remove dependency, add-file-dependency, add/register/remove workspace module.
 *
 * <p>{@code add-dependency} writes the selector it is handed and reports it in
 * {@link Result#detail}; the verb resolves {@code latest} to a number before calling here.
 */
public final class EditOps {

    /**
     * {@code changed} false = the edit was a no-op (content already as requested). {@code detail}
     * is the sha256 for {@code add-file-dependency} and the version literal written for
     * {@code add-dependency}.
     */
    public record Result(boolean changed, @Nullable String error, String detail) {
        public Result(boolean changed, String error) {
            this(changed, error, "");
        }
    }

    private EditOps() {}

    public static Result apply(Path file, @Nullable String op, List<String> args) {
        try {
            String original = Files.readString(file, StandardCharsets.UTF_8);
            String detail = "";
            String updated;
            if ("add-file-dependency".equals(op)) {
                FileDep fd = addFileDependency(original, args);
                updated = fd.toml();
                detail = fd.sha256();
            } else if ("add-dependency".equals(op)) {
                String version = args.get(4);
                updated = JkBuildEditor.addDependency(
                        original,
                        Scope.fromCanonical(args.get(0)),
                        args.get(1),
                        args.get(2),
                        args.get(3),
                        version,
                        LibraryCatalog.forProject(
                                Objects.requireNonNull(file.toAbsolutePath().getParent(), "manifest directory")));
                detail = version;
            } else {
                updated = switch (op == null ? "" : op) {
                    case "remove-dependency" ->
                        JkBuildEditor.removeDependency(original, Scope.fromCanonical(args.get(0)), args.get(1));
                    case "add-workspace-module" -> JkBuildEditor.addWorkspaceModule(original, args.get(0));
                    case "register-workspace-module" -> JkBuildEditor.registerWorkspaceModule(original, args.get(0));
                    case "remove-workspace-module" -> JkBuildEditor.removeWorkspaceModule(original, args.get(0));
                    case "set-artifacts" ->
                        JkBuildEditor.setArtifacts(
                                original, Boolean.parseBoolean(args.get(0)), Boolean.parseBoolean(args.get(1)));
                    default -> throw new IllegalArgumentException("unknown edit op: " + op);
                };
            }
            if (updated.equals(original)) return new Result(false, null, detail);
            if (op != null && op.endsWith("-dependency")) {
                // Dependency-policy guards judge the proposed manifest before it is written; a
                // violation refuses the edit with `instead` and `why`. No flag overrides this.
                String refusal = MutationCheck.check(file, updated);
                if (refusal != null) return new Result(false, refusal, detail);
            }
            Files.writeString(file, updated, StandardCharsets.UTF_8);
            return new Result(true, null, detail);
        } catch (IOException | RuntimeException e) {
            return new Result(false, Errors.text(e));
        }
    }

    private record FileDep(String toml, String sha256) {}

    /** Args: scope, library, group, artifact, version, filePath. Engine hashes and CAS-puts. */
    private static FileDep addFileDependency(String original, List<String> args) throws IOException {
        Path src = Path.of(args.get(5));
        String sha256 = Hashing.sha256Hex(src);
        JkStores.storeCas().putFile(src, sha256);
        String toml = JkBuildEditor.addFileDependency(
                original, Scope.fromCanonical(args.get(0)), args.get(1), args.get(2), args.get(3), args.get(4), sha256);
        return new FileDep(toml, sha256);
    }
}
