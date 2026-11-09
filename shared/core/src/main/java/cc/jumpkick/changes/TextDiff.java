// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.changes;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A unified line diff of two versions of a small text file, for the change journal. The files it
 * diffs are hand-sized ({@code config.toml}, a shell profile), so a plain longest-common-subsequence
 * table is enough; a file too large for that is summarized by its line counts instead.
 */
public final class TextDiff {

    /** What a masked value becomes. */
    public static final String MASK = "***";

    /** Lines of context around each change, as {@code diff -u} prints. */
    static final int CONTEXT = 3;

    /** Above this many lines a side, the table would be too large: summarize instead. */
    static final int MAX_LINES = 2_000;

    /**
     * The keys whose values are credentials in a {@code [repositories.*]} table, as the config schema
     * declares them. Their values are masked whatever they hold, a {@code ${VAR}} reference included.
     */
    static final Set<String> CREDENTIAL_KEYS = Set.of("password", "token");

    private static final Pattern TABLE = Pattern.compile("^\\s*\\[\\s*([^\\]]+?)\\s*]");

    private static final Pattern KEY_VALUE = Pattern.compile("^(\\s*)([A-Za-z0-9_.-]+)(\\s*=\\s*)(.*)$");

    private TextDiff() {}

    /**
     * {@code before} to {@code after} as a unified diff with the credential values of a TOML file
     * masked on both sides; empty when they are the same. A null side is an absent file.
     */
    public static String unified(String name, String before, String after) {
        List<String> a = maskToml(lines(before));
        List<String> b = maskToml(lines(after));
        if (a.equals(b)) return "";
        if (a.size() > MAX_LINES || b.size() > MAX_LINES) {
            return "--- " + name + "\n+++ " + name + "\n(" + a.size() + " lines before, " + b.size() + " after)\n";
        }
        return hunks(name, a, b);
    }

    /**
     * {@code lines} with each credential value of a {@code [repositories.*]} table replaced by
     * {@link #MASK}. Masking is by the schema's declaration of which keys hold credentials, not by
     * guessing from a value.
     */
    static List<String> maskToml(List<String> lines) {
        List<String> out = new ArrayList<>(lines.size());
        boolean repositories = false;
        for (String line : lines) {
            Matcher t = TABLE.matcher(line);
            if (t.find()) {
                String table = t.group(1);
                repositories = table.equals("repositories") || table.startsWith("repositories.");
                out.add(line);
                continue;
            }
            Matcher kv = KEY_VALUE.matcher(line);
            if (kv.matches()) {
                String key = kv.group(2);
                String leaf = key.substring(key.lastIndexOf('.') + 1);
                boolean dotted = key.startsWith("repositories.");
                if ((repositories || dotted) && CREDENTIAL_KEYS.contains(leaf)) {
                    out.add(kv.group(1) + key + kv.group(3) + "\"" + MASK + "\"");
                    continue;
                }
                if ((repositories || dotted) && kv.group(4).startsWith("{")) {
                    out.add(maskInline(line));
                    continue;
                }
            }
            out.add(line);
        }
        return out;
    }

    /** An inline table's credential members masked: {@code { username = "u", password = "***" }}. */
    private static String maskInline(String line) {
        String out = line;
        for (String key : CREDENTIAL_KEYS) {
            out = out.replaceAll("(\\b" + key + "\\s*=\\s*)(\"[^\"]*\"|'[^']*')", "$1\"" + MASK + "\"");
        }
        return out;
    }

    private static List<String> lines(String text) {
        if (text.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(List.of(text.split("\r?\n", -1)));
        if (!out.isEmpty() && out.getLast().isEmpty()) out.removeLast();
        return out;
    }

    private static String hunks(String name, List<String> a, List<String> b) {
        int n = a.size();
        int m = b.size();
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        // One op per line: ' ' kept, '-' removed, '+' added, with the line numbers on each side.
        StringBuilder ops = new StringBuilder();
        List<String> text = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n || j < m) {
            if (i < n && j < m && a.get(i).equals(b.get(j))) {
                ops.append(' ');
                text.add(a.get(i));
                i++;
                j++;
            } else if (i < n && (j == m || lcs[i + 1][j] >= lcs[i][j + 1])) {
                ops.append('-');
                text.add(a.get(i));
                i++;
            } else {
                ops.append('+');
                text.add(b.get(j));
                j++;
            }
        }
        StringBuilder out = new StringBuilder("--- ")
                .append(name)
                .append("\n+++ ")
                .append(name)
                .append('\n');
        int k = 0;
        int lineA = 1;
        int lineB = 1;
        while (k < ops.length()) {
            if (ops.charAt(k) == ' ') {
                k++;
                lineA++;
                lineB++;
                continue;
            }
            int start = Math.max(0, k - CONTEXT);
            int startA = lineA - (k - start);
            int startB = lineB - (k - start);
            int end = k;
            int quiet = 0;
            while (end < ops.length() && quiet <= 2 * CONTEXT) {
                quiet = ops.charAt(end) == ' ' ? quiet + 1 : 0;
                end++;
            }
            int stop = Math.min(ops.length(), end - Math.max(0, quiet - CONTEXT));
            int countA = 0;
            int countB = 0;
            StringBuilder body = new StringBuilder();
            for (int x = start; x < stop; x++) {
                char op = ops.charAt(x);
                if (op != '+') countA++;
                if (op != '-') countB++;
                body.append(op).append(text.get(x)).append('\n');
            }
            out.append("@@ -")
                    .append(startA)
                    .append(',')
                    .append(countA)
                    .append(" +")
                    .append(startB)
                    .append(',')
                    .append(countB)
                    .append(" @@\n")
                    .append(body);
            for (int x = k; x < stop; x++) {
                char op = ops.charAt(x);
                if (op != '+') lineA++;
                if (op != '-') lineB++;
            }
            k = stop;
        }
        return out.toString();
    }
}
