// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.house;

import cc.jumpkick.config.RequestEnvNames;
import cc.jumpkick.guard.api.Blank;
import cc.jumpkick.guard.api.Guard;
import cc.jumpkick.guard.api.GuardSuite;
import cc.jumpkick.guard.api.Scope;
import cc.jumpkick.guard.api.Text;
import cc.jumpkick.guard.api.TextSite;
import cc.jumpkick.guard.api.Violations;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every variable the engine reads from a request's environment by name is one the client
 * forwards. The client sends the engine only {@link RequestEnvNames} and what something declares,
 * so a name the engine reads off that list reads as unset on every request: a CI that never
 * detects, a credential chain that never finds its profile.
 */
@GuardSuite(scope = Scope.WORKSPACE)
final class RequestEnvRules {

    /** A read through the request's environment: {@code BuildEnv.get("X")}, or an env function or map handed one. */
    private static final Pattern READ =
            Pattern.compile("\\b(?:BuildEnv\\.get|env\\.apply|env\\.get)\\(\\s*\"([A-Za-z_][A-Za-z0-9_]*)\"\\s*\\)");

    /** The engine's own code: what runs in the engine process, not in a worker or the client. */
    private static final List<String> ENGINE_SOURCES =
            List.of("shared/*/src/main/**/*.java", "server/*/src/main/**/*.java");

    @Guard(
            id = "request-env-forwarded",
            why =
                    "the client forwards the engine only the variables it reads for itself and what a manifest or the user's configuration declares, so a name the engine reads off that list is always unset",
            instead =
                    "add the name or its prefix to RequestEnvNames, or declare it where the project or user configures it")
    void requestEnvForwarded(Text text, Violations v) {
        int reads = 0;
        for (String glob : ENGINE_SOURCES) {
            for (String rel : text.files(glob)) {
                String src = text.blanked(rel, Blank.COMMENTS);
                if (!src.contains("env.") && !src.contains("BuildEnv.get")) continue;
                Matcher m = READ.matcher(src);
                while (m.find()) {
                    reads++;
                    String name = m.group(1);
                    if (RequestEnvNames.engineReads(name, false)) continue;
                    v.add(
                            new TextSite(rel, line(src, m.start()), name),
                            name + " is read from the request but the client never sends it:"
                                    + " add it to RequestEnvNames");
                }
            }
        }
        if (reads < 40)
            throw new IllegalStateException(
                    "environment reads found in engine sources: " + reads + " — 73 when measured; the walk moved");
        v.population(reads);
    }

    private static int line(String code, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) if (code.charAt(i) == '\n') line++;
        return line;
    }
}
