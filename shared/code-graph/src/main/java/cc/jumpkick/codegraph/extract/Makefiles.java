// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph.extract;

import cc.jumpkick.codegraph.Anchors;
import cc.jumpkick.codegraph.Confidence;
import cc.jumpkick.codegraph.Fragment;
import cc.jumpkick.codegraph.FragmentBuilder;
import cc.jumpkick.codegraph.Mention;
import cc.jumpkick.codegraph.Relations;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makefiles, as users of anchors. Make reads every environment variable as a variable of its own, so
 * {@code $(VAR)} and {@code ${VAR}} named as a variable are inferred mentions of it, linked only to
 * one something else sets or reads; {@code VAR ?= default} reads it, declared; {@code export VAR}
 * sets it for the recipes, declared. A recipe line is a shell line ({@code $$VAR} read, URLs to this
 * service requested). Comments are skipped; targets and prerequisites are not read yet.
 */
final class Makefiles {

    private Makefiles() {}

    private static final Pattern REFERENCE = Pattern.compile("\\$[({]([A-Za-z_][A-Za-z0-9_]*)[)}]");
    private static final Pattern EXPORT = Pattern.compile("^\\s*export\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?:[:?+!]*=|$)");
    private static final Pattern DEFAULTED = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\?=");

    /** True for {@code Makefile}, {@code makefile}, {@code GNUmakefile} and {@code *.mk}. */
    static boolean isMakefile(String name) {
        return name.equals("Makefile")
                || name.equals("makefile")
                || name.equals("GNUmakefile")
                || name.toLowerCase(Locale.ROOT).endsWith(".mk");
    }

    static Fragment extract(String relPath, String text) {
        FragmentBuilder b = new FragmentBuilder(relPath, "code", "makefile");
        List<String> lines = Lines.split(text);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int n = i + 1;
            boolean recipe = line.startsWith("\t");
            if (line.stripLeading().startsWith("#")) continue;
            // $$ is a dollar the shell sees: kept out of Make's references, then given back to the shell.
            String make = line.replace("$$", "\u0000");
            Matcher r = REFERENCE.matcher(make);
            while (r.find()) {
                String name = r.group(1);
                if (EnvVars.isEnvName(name) && !EnvVars.isAmbient(name)) {
                    b.mention(b.fileId(), Mention.Kind.ENV, name, n);
                }
            }
            if (recipe) {
                String shell = REFERENCE.matcher(make).replaceAll("").replace('\u0000', '$');
                Requests.urls(b, b.fileId(), shell, n);
                EnvVars.shellLine(b, shell, n);
                continue;
            }
            Matcher e = EXPORT.matcher(line);
            if (e.find()) {
                EnvVars.define(b, e.group(1), n);
                continue;
            }
            Matcher d = DEFAULTED.matcher(line);
            if (d.find() && EnvVars.isEnvName(d.group(1)) && !EnvVars.isAmbient(d.group(1))) {
                b.anchor(b.fileId(), Anchors.ENV, d.group(1), Relations.USES, Confidence.DECLARED, n);
            }
            Requests.urls(b, b.fileId(), line, n);
        }
        return b.build();
    }
}
