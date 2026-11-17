// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph.extract;

import cc.jumpkick.codegraph.Anchors;
import cc.jumpkick.codegraph.Confidence;
import cc.jumpkick.codegraph.Fragment;
import cc.jumpkick.codegraph.FragmentBuilder;
import cc.jumpkick.codegraph.Mention;
import cc.jumpkick.codegraph.Relations;
import cc.jumpkick.host.ManifestNames;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Environment variables: where files set them ({@code .env}, a Dockerfile's {@code ENV} and {@code
 * ARG}, an {@code export} in a script, a Makefile or a workflow step, a workflow step's {@code
 * $GITHUB_ENV}, compose {@code environment:}, workflow and Kubernetes {@code env:}) and where they
 * read them ({@code ${VAR}} and {@code $VAR}). A file that sets a variable is
 * a declared definition; an explicit {@code ${VAR}} in a Dockerfile or a Spring placeholder a
 * declared use; a {@code $VAR} in a script or a YAML value a mention the linker links only to a
 * variable something sets or reads, inferred. Only names are kept: no value is ever read into the
 * graph.
 */
public final class EnvVars {

    private EnvVars() {}

    private static final Pattern ENV_NAME = Pattern.compile("[A-Z_][A-Z0-9_]*");
    private static final Pattern DOT_ENV = Pattern.compile("^\\s*(?:export\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*=");
    private static final Pattern EXPORT =
            Pattern.compile("(?:^\\s*(?:-\\s+)?(?:run:\\s*)?|[;&|]\\s*)export\\s+([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern GITHUB_ENV =
            Pattern.compile("\\b([A-Z_][A-Z0-9_]*)=[^>]*>>\\s*[\"']?\\$\\{?GITHUB_ENV\\b");
    private static final Pattern EXPOSE = Pattern.compile("(?i)^\\s*EXPOSE\\s+(.*)$");
    private static final Pattern REFERENCE =
            Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?:[:?+-][^}]*)?}|\\$([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern WORKFLOW_ENV = Pattern.compile("\\$\\{\\{\\s*env\\.([A-Za-z_][A-Za-z0-9_]*)\\s*}}");
    private static final Pattern INSTRUCTION = Pattern.compile("(?i)^\\s*(ENV|ARG)\\s+(.*)$");
    private static final Pattern ENV_PAIR = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)=(?:\"[^\"]*\"|'[^']*'|\\S*)");

    /** Variables every shell and image has: a read of one says nothing about this project. */
    private static final Set<String> AMBIENT = Set.of(
            "HOME",
            "PATH",
            "PWD",
            "OLDPWD",
            "USER",
            "SHELL",
            "TERM",
            "LANG",
            "TMPDIR",
            "HOSTNAME",
            "IFS",
            "UID",
            "RANDOM",
            "SECONDS",
            "LINENO",
            "BASH_SOURCE",
            "OSTYPE",
            "CI",
            "GITHUB_WORKSPACE",
            "GITHUB_ENV",
            "GITHUB_OUTPUT",
            "GITHUB_PATH",
            "GITHUB_SHA",
            "GITHUB_REF",
            "RUNNER_TEMP",
            "RUNNER_OS");

    /** True for a name written as an environment variable: upper case, digits and underscores. */
    public static boolean isEnvName(String name) {
        return ENV_NAME.matcher(name).matches() && name.chars().anyMatch(Character::isLetter);
    }

    /** True for a {@code .env} file: {@code .env}, {@code .env.local}, {@code prod.env}. */
    static boolean isDotEnv(String name) {
        return name.equals(ManifestNames.ENV)
                || name.startsWith(ManifestNames.ENV + ".")
                || name.endsWith(ManifestNames.ENV);
    }

    /** True for a Dockerfile: {@code Dockerfile}, {@code Dockerfile.native}, {@code app.Dockerfile}, {@code Containerfile}. */
    static boolean isDockerfile(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.equals("dockerfile")
                || n.startsWith("dockerfile.")
                || n.endsWith(".dockerfile")
                || n.equals("containerfile");
    }

    /** A {@code .env} file: each key it sets, never its value. */
    static Fragment dotEnv(String relPath, String text) {
        FragmentBuilder b = new FragmentBuilder(relPath, "document", "env");
        List<String> lines = Lines.split(text);
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = DOT_ENV.matcher(lines.get(i));
            if (m.find()) define(b, m.group(1), i + 1);
        }
        return b.build();
    }

    /**
     * A Dockerfile: {@code ENV} and {@code ARG} set variables, declared; {@code ${VAR}} and {@code $VAR}
     * read them, declared; {@code EXPOSE} defines ports ({@link Ports}); command lines may request this
     * service's routes.
     */
    static Fragment dockerfile(String relPath, String text, Set<String> programs) {
        FragmentBuilder b = new FragmentBuilder(relPath, "code", "dockerfile");
        List<String> lines = Lines.split(text);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.stripLeading().startsWith("#")) continue;
            int n = i + 1;
            Matcher ins = INSTRUCTION.matcher(line);
            if (ins.matches()) {
                String rest = ins.group(2).strip();
                Matcher pair = ENV_PAIR.matcher(rest);
                boolean any = false;
                while (pair.find()) {
                    define(b, pair.group(1), n);
                    any = true;
                }
                if (!any) {
                    String name = rest.split("\\s+", 2)[0];
                    if (!name.isEmpty() && name.matches("[A-Za-z_][A-Za-z0-9_]*")) define(b, name, n);
                }
                continue;
            }
            Matcher expose = EXPOSE.matcher(line);
            if (expose.matches()) Ports.expose(b, expose.group(1), n);
            Matcher r = REFERENCE.matcher(line);
            while (r.find()) {
                String name = r.group(1) != null ? r.group(1) : r.group(2);
                if (!AMBIENT.contains(name)) {
                    b.anchor(b.fileId(), Anchors.ENV, name, Relations.USES, Confidence.DECLARED, n);
                }
            }
            Requests.urls(b, b.fileId(), line, n);
            CommandLines.mentions(b, programs, b.fileId(), line, n);
        }
        return b.build();
    }

    /**
     * One script line: {@code export VAR} and a workflow step's {@code echo "VAR=…" >> $GITHUB_ENV} set
     * it; {@code $VAR} reads it, inferred.
     */
    static void shellLine(FragmentBuilder b, String line, int n) {
        if (line.contains("export")) {
            Matcher e = EXPORT.matcher(line);
            while (e.find()) define(b, e.group(1), n);
        }
        if (line.contains("GITHUB_ENV")) {
            Matcher g = GITHUB_ENV.matcher(line);
            while (g.find()) define(b, g.group(1), n);
        }
        references(b, line, n);
    }

    /** {@code ${VAR}}, {@code $VAR} and a workflow's {@code ${{ env.VAR }}} in {@code text}, as inferred mentions. */
    static void references(FragmentBuilder b, String text, int n) {
        if (text.indexOf('$') < 0) return;
        Matcher r = REFERENCE.matcher(text);
        while (r.find()) {
            String name = r.group(1) != null ? r.group(1) : r.group(2);
            if (isEnvName(name) && !AMBIENT.contains(name)) b.mention(b.fileId(), Mention.Kind.ENV, name, n);
        }
        Matcher w = WORKFLOW_ENV.matcher(text);
        while (w.find()) b.mention(b.fileId(), Mention.Kind.ENV, w.group(1), n);
    }

    /**
     * A YAML key, by its path: a key under {@code env:} or {@code environment:} (compose, a workflow), or
     * the {@code name:} of a Kubernetes {@code env:} entry, sets that variable.
     */
    static void yamlKey(FragmentBuilder b, List<String> path, String value, int n) {
        int last = path.size() - 1;
        if (last < 1) return;
        String parent = path.get(last - 1);
        String key = path.get(last);
        if ((parent.equals("env") || parent.equals("environment")) && isEnvName(key)) {
            define(b, key, n);
        } else if (key.equals("name") && parent.equals("env") && isEnvName(unquote(value))) {
            define(b, unquote(value), n);
        }
    }

    private static final Pattern LIST_ENTRY = Pattern.compile("^\\s*-\\s+([A-Z_][A-Z0-9_]*)\\s*(?:=|$)");

    /** A compose {@code environment:} list entry, {@code - VAR=value}, under {@code path}: it sets VAR. */
    static void yamlListEntry(FragmentBuilder b, List<String> path, String line, int n) {
        if (path.isEmpty()) return;
        String parent = path.getLast();
        if (!parent.equals("environment") && !parent.equals("env")) return;
        Matcher m = LIST_ENTRY.matcher(line);
        if (m.find()) define(b, m.group(1), n);
    }

    /** True for a variable every shell and image has, whose read says nothing about this project. */
    static boolean isAmbient(String name) {
        return AMBIENT.contains(name);
    }

    static void define(FragmentBuilder b, String name, int line) {
        b.anchor(b.fileId(), Anchors.ENV, name, Relations.DEFINES, Confidence.DECLARED, line);
    }

    private static String unquote(String s) {
        String t = s.strip();
        if (t.length() >= 2 && (t.startsWith("\"") && t.endsWith("\"") || t.startsWith("'") && t.endsWith("'"))) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }
}
