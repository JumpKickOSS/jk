// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph.extract;

import cc.jumpkick.codegraph.Anchors;
import cc.jumpkick.codegraph.Confidence;
import cc.jumpkick.codegraph.FragmentBuilder;
import cc.jumpkick.codegraph.Mention;
import cc.jumpkick.codegraph.Relations;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Network ports: where a file says a service listens ({@code server.port} in a Spring config file, a
 * Dockerfile's {@code EXPOSE}, the published side of a compose {@code ports:} entry, a Kubernetes
 * {@code containerPort}), each a declared definition; the container side of a compose mapping, a
 * declared use; and a URL to this machine ({@code localhost:8080}), a mention the linker links only
 * to a port something defines or uses, inferred.
 */
final class Ports {

    private Ports() {}

    private static final Pattern NUMBER = Pattern.compile("\\d{1,5}");
    private static final Pattern EXPOSED = Pattern.compile("(\\d{1,5})(?:/(?:tcp|udp|sctp))?");
    private static final Pattern LOCAL = Pattern.compile(
            "(?:localhost|127\\.0\\.0\\.1|0\\.0\\.0\\.0|\\[::1]):(\\d{1,5})(?![\\d.:])", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEFAULTED = Pattern.compile("^\\$\\{[A-Za-z0-9_.\\-]+:(\\d{1,5})}$");
    private static final Pattern ENTRY = Pattern.compile("^\\s*-\\s+(.*)$");

    /** True for a TCP or UDP port number: 1 to 65535. */
    static boolean isPort(String s) {
        if (!NUMBER.matcher(s).matches()) return false;
        int p = Integer.parseInt(s);
        return p > 0 && p <= 65535;
    }

    /** A Dockerfile's {@code EXPOSE 8080 8443/tcp}: each port the image listens on, declared. */
    static void expose(FragmentBuilder b, String rest, int n) {
        for (String word : rest.strip().split("\\s+")) {
            Matcher m = EXPOSED.matcher(word);
            if (m.matches() && isPort(m.group(1))) define(b, m.group(1), n);
        }
    }

    /** The ports URLs on {@code line} name on this machine, {@code http://localhost:8080/...}, as inferred mentions. */
    static void urls(FragmentBuilder b, String from, String line, int n) {
        if (line.indexOf(':') < 0) return;
        Matcher m = LOCAL.matcher(line);
        while (m.find()) {
            if (isPort(m.group(1))) b.mention(from, Mention.Kind.PORT, m.group(1), n);
        }
    }

    /**
     * A Spring config key: {@code server.port} with a number or a {@code ${PORT:8080}} default is the
     * port the service listens on, declared.
     */
    static void springKey(FragmentBuilder b, String key, String value, int n) {
        if (!Anchors.relaxed(key).equals("server.port")) return;
        String v = unquote(value);
        Matcher d = DEFAULTED.matcher(v);
        String port = d.matches() ? d.group(1) : v;
        if (isPort(port)) define(b, port, n);
    }

    /**
     * A YAML key by its path: the {@code published:} port of compose's long {@code ports:} form and a
     * Kubernetes {@code containerPort:}, each a definition. The long form's container side is not
     * read: its key is the word house rules keep for the build output directory.
     */
    static void yamlKey(FragmentBuilder b, List<String> path, String value, int n) {
        int last = path.size() - 1;
        if (last < 1) return;
        String key = path.get(last);
        String port = unquote(value);
        if (!isPort(port)) return;
        if (key.equals("containerPort")) {
            define(b, port, n);
        } else if (key.equals("published") && path.get(last - 1).equals("ports")) {
            define(b, port, n);
        }
    }

    /**
     * A compose {@code ports:} list entry under {@code path}, {@code - "8081:8080"} ({@code
     * [ip:][published:]target[/protocol]}): the published port is defined, the container's used;
     * a range is skipped.
     */
    static void yamlListEntry(FragmentBuilder b, List<String> path, String line, int n) {
        if (path.isEmpty() || !path.getLast().equals("ports")) return;
        Matcher e = ENTRY.matcher(line);
        if (!e.matches()) return;
        String entry = unquote(e.group(1).replaceFirst("\\s+#.*$", ""));
        int slash = entry.indexOf('/');
        if (slash >= 0) entry = entry.substring(0, slash);
        String[] parts = entry.split(":");
        String target = parts[parts.length - 1];
        if (!isPort(target)) return;
        if (parts.length >= 2 && isPort(parts[parts.length - 2])) define(b, parts[parts.length - 2], n);
        use(b, target, n);
    }

    private static void define(FragmentBuilder b, String port, int n) {
        b.anchor(b.fileId(), Anchors.PORT, port, Relations.DEFINES, Confidence.DECLARED, n);
    }

    private static void use(FragmentBuilder b, String port, int n) {
        b.anchor(b.fileId(), Anchors.PORT, port, Relations.USES, Confidence.DECLARED, n);
    }

    private static String unquote(String s) {
        String t = s.strip();
        if (t.length() >= 2 && (t.startsWith("\"") && t.endsWith("\"") || t.startsWith("'") && t.endsWith("'"))) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }
}
