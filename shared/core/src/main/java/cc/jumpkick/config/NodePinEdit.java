// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Writes a manifest's Node.js version: {@code [node] version} when the manifest has a {@code [node]}
 * table (TOML cannot hold a {@code node} key beside it), else the root {@code node} key.
 */
public final class NodePinEdit {

    private static final Pattern NODE_HEADER = Pattern.compile("^\\s*\\[node]\\s*(#.*)?$");
    private static final Pattern VERSION_KEY = Pattern.compile("^\\s*version\\s*=");
    private static final Pattern ANY_HEADER = Pattern.compile("^\\s*\\[.*$");

    private NodePinEdit() {}

    /**
     * {@code content} with its Node.js version set to {@code value}, already TOML-encoded ({@code 24}
     * or {@code "=24.21.0"}).
     *
     * @throws JkBuildParseException when the result does not parse
     */
    public static String apply(String content, String value) {
        List<String> lines = new ArrayList<>(List.of(content.split("\n", -1)));
        int header = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (NODE_HEADER.matcher(lines.get(i)).matches()) {
                header = i;
                break;
            }
        }
        String out;
        if (header < 0) {
            out = JkBuildEditor.setRootScalar(content, "node", value);
        } else if (hasVersion(lines, header)) {
            out = JkBuildEditor.setTableScalar(content, "node", "version", value);
        } else {
            lines.add(header + 1, "version = " + value);
            out = String.join("\n", lines);
        }
        JkBuildParser.parse(out);
        return out;
    }

    /** The value {@code spec} is written as: a bare major unquoted, anything else a string. */
    public static String tomlValue(String spec) {
        String s = spec.trim();
        if (s.matches("\\d+")) return s;
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) return s;
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static boolean hasVersion(List<String> lines, int header) {
        for (int i = header + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (ANY_HEADER.matcher(line).matches()) return false;
            if (VERSION_KEY.matcher(line).find()) return true;
        }
        return false;
    }
}
