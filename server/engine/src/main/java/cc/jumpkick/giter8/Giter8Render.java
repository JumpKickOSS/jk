// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.giter8;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.stringtemplate.v4.AutoIndentWriter;
import org.stringtemplate.v4.ST;
import org.stringtemplate.v4.STGroup;
import org.stringtemplate.v4.misc.STMessage;

/** StringTemplate 4 render with Giter8 path {@code $name__Camel$} and package-dir paths. */
final class Giter8Render {

    private static final Pattern PATH_FORMAT = Pattern.compile("\\$([A-Za-z0-9_.-]+)__([A-Za-z0-9_,.-]+)\\$");

    private Giter8Render() {}

    static String content(String template, Map<String, String> props) throws IOException {
        return render(template, props, false);
    }

    /**
     * Render a template-relative path. {@code null} means omit the file (failed {@code $if$}
     * without a {@code .} flatten).
     */
    static @Nullable String path(String template, Map<String, String> props) throws IOException {
        return collapse(render(rewritePathFormats(template), props, true));
    }

    private static String render(String template, Map<String, String> props, boolean pathMode) throws IOException {
        if (template == null || template.isEmpty()) return template == null ? "" : template;
        STGroup group = new STGroup('$', '$');
        group.registerRenderer(Object.class, new Giter8Formats());
        StringBuilder errors = new StringBuilder();
        group.setListener(new org.stringtemplate.v4.STErrorListener() {
            @Override
            public void compileTimeError(STMessage msg) {
                errors.append(msg).append('\n');
            }

            @Override
            public void runTimeError(STMessage msg) {
                errors.append(msg).append('\n');
            }

            @Override
            public void IOError(STMessage msg) {
                errors.append(msg).append('\n');
            }

            @Override
            public void internalError(STMessage msg) {
                errors.append(msg).append('\n');
            }
        });
        ST st = new ST(group, template);
        Map<String, String> attrs = pathMode ? pathAttrs(props) : props;
        for (Map.Entry<String, String> e : attrs.entrySet()) {
            st.add(e.getKey(), new Giter8Value(e.getValue()));
        }
        String out;
        try {
            // ST's default writer emits line.separator; scaffolds must be byte-identical LF on
            // every OS, so the newline is explicit.
            StringWriter buf = new StringWriter(template.length() + 64);
            AutoIndentWriter wr = new AutoIndentWriter(buf, "\n");
            st.write(wr);
            out = buf.toString();
        } catch (IOException | RuntimeException e) {
            throw new IOException("giter8 template failed: " + e.getMessage(), e);
        }
        if (!errors.isEmpty()) {
            throw new IOException("giter8 template failed: " + errors.toString().strip());
        }
        return out;
    }

    static String rewritePathFormats(String path) {
        Matcher m = PATH_FORMAT.matcher(path);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement("$" + m.group(1) + ";format=\"" + m.group(2) + "\"$"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static Map<String, String> pathAttrs(Map<String, String> props) {
        Map<String, String> out = new LinkedHashMap<>(props);
        for (Map.Entry<String, String> e : props.entrySet()) {
            String key = e.getKey();
            if ("package".equals(key) || key.endsWith(".package")) {
                out.put(key, e.getValue().replace('.', '/'));
            }
        }
        return out;
    }

    /**
     * Drop {@code .} flatten segments. Empty segments mean a failed {@code $if$} without flatten —
     * omit the file. A leading {@code /} is treated the same (the if ate the first component).
     */
    static @Nullable String collapse(String rel) {
        if (rel == null || rel.isEmpty()) return null;
        String norm = rel.replace('\\', '/');
        List<String> parts = new ArrayList<>();
        for (String part : norm.split("/", -1)) {
            if (part.equals(".")) continue;
            if (part.isEmpty()) return null;
            parts.add(part);
        }
        if (parts.isEmpty()) return null;
        return String.join("/", parts);
    }
}
