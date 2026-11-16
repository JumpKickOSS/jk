// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph.extract;

import cc.jumpkick.codegraph.Fragment;
import cc.jumpkick.codegraph.FragmentBuilder;
import java.util.List;

/**
 * Shell scripts, as users of anchors: the requests to this service their command lines make
 * ({@code curl http://localhost:8080/api/orders}). Comments are skipped. What a script runs, sources
 * and exports is not read yet.
 */
final class ShellScripts {

    private ShellScripts() {}

    static Fragment extract(String relPath, String text) {
        FragmentBuilder b = new FragmentBuilder(relPath, "code", "shell");
        List<String> lines = Lines.split(text);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.stripLeading().startsWith("#")) continue;
            Requests.urls(b, b.fileId(), line, i + 1);
        }
        return b.build();
    }
}
