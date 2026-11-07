// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.rules;

import static cc.jumpkick.config.WorkspaceModules.expand;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.UserConfigParseException;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The workspace's member directories in path order, for the loader's member layer; the root alone when it has none. */
final class WorkspaceMembers {

    private WorkspaceMembers() {}

    static List<Path> of(Path root) throws IOException {
        Path manifest = ManifestPaths.manifestIn(root);
        List<Path> out = new ArrayList<>();
        if (!Files.isRegularFile(manifest)) return out;
        List<String> rels;
        try {
            JkBuild build = JkBuildParser.parse(manifest);
            rels = expand(root, build.workspaceModules()); // the build's one expansion, globs included
        } catch (UserConfigParseException unparsed) {
            throw unparsed; // config.toml's own error, with its position — never this site's fallback
        } catch (RuntimeException unparseable) {
            return out; // parse-build reports it
        }
        for (String m : rels) out.add(root.resolve(m).normalize());
        out.sort(null);
        return out;
    }
}
