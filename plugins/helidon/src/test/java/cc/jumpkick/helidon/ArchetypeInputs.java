// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.helidon;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * The inputs of Helidon's project archetypes ({@code helidon init}, archetype v2), read from the
 * scripts under {@code archetypes/archetypes/src/main/archetype} in the Helidon repository: every
 * {@code <boolean>}, {@code <enum>}, {@code <list>} and {@code <text>} with an {@code id}, as one line
 * {@code <path> <kind> [<option>|<option>…]}. The path joins the ids of the inputs it is nested in
 * within its script ({@code media.json-lib}), and the options are an enum's or list's direct
 * {@code <option value>}s in document order.
 *
 * <p>The checked-in snapshot ({@code archetype-inputs.txt}) holds these lines for one Helidon tag,
 * under a {@code tag = <version>} line; {@link HelidonArchetypeOptionsTest} diffs the templates'
 * questions against it, and {@link HelidonArchetypeUpstreamTest} re-derives it from GitHub.
 */
final class ArchetypeInputs {

    /** Where the archetype scripts live in the Helidon repository. */
    static final String ARCHETYPE_DIR = "archetypes/archetypes/src/main/archetype/";

    /** The snapshot on the test classpath. */
    static final String SNAPSHOT = "/archetype-inputs.txt";

    private static final String NS = "https://helidon.io/archetype/2.0";
    private static final Set<String> KINDS = Set.of("boolean", "enum", "list", "text");

    /** One archetype input: its path, kind, and an enum's or list's options. */
    record Input(String path, String kind, List<String> options) {

        String line() {
            return options.isEmpty() ? path + " " + kind : path + " " + kind + " " + String.join("|", options);
        }

        static Input parse(String line) {
            String[] parts = line.strip().split(" ");
            if (parts.length < 2 || parts.length > 3) throw new IllegalArgumentException("not an input line: " + line);
            return new Input(parts[0], parts[1], parts.length == 3 ? List.of(parts[2].split("\\|")) : List.of());
        }
    }

    /** The snapshot: the tag it was read at, and its inputs by path. */
    record Snapshot(String tag, Map<String, Input> inputs) {

        /** The lines {@link #parse} reads back, header first. */
        String text() {
            StringBuilder out =
                    new StringBuilder(HEADER).append("tag = ").append(tag).append('\n');
            for (Input in : inputs.values()) out.append(in.line()).append('\n');
            return out.toString();
        }

        static Snapshot parse(String text) {
            String tag = null;
            Map<String, Input> inputs = new TreeMap<>();
            for (String raw : text.split("\n")) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("tag = ")) {
                    tag = line.substring("tag = ".length()).strip();
                    continue;
                }
                Input in = Input.parse(line);
                if (inputs.put(in.path(), in) != null) throw new IllegalArgumentException("two lines for " + in.path());
            }
            if (tag == null) throw new IllegalArgumentException("the snapshot names no tag (tag = <version>)");
            return new Snapshot(tag, inputs);
        }

        static Snapshot read() {
            try (InputStream in = ArchetypeInputs.class.getResourceAsStream(SNAPSHOT)) {
                if (in == null) throw new IllegalStateException(SNAPSHOT + " is not on the test classpath");
                return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    static final String HEADER = """
            # The inputs of Helidon's project archetypes (helidon init): every <boolean>, <enum>, <list> and
            # <text> with an id under archetypes/archetypes/src/main/archetype at the tag below, one per line
            # as <path> <kind> [<options>]. HelidonArchetypeOptionsTest diffs the helidon/* templates'
            # questions against it. HelidonArchetypeUpstreamTest (network tier) re-derives it from GitHub, and
            # with HELIDON_ARCHETYPE_CAPTURE=<file> writes what it read there.
            """;

    private ArchetypeInputs() {}

    /** The inputs of {@code scripts} (each an archetype XML script), by path. */
    static Map<String, Input> of(Collection<byte[]> scripts) {
        Map<String, Input> out = new TreeMap<>();
        for (byte[] script : scripts) {
            Element root = parse(script).getDocumentElement();
            walk(root, new ArrayList<>(), out);
        }
        return out;
    }

    private static void walk(Element el, List<String> prefix, Map<String, Input> out) {
        for (Node n = el.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element child)) continue;
            String id = child.getAttribute("id");
            if (NS.equals(child.getNamespaceURI()) && KINDS.contains(child.getLocalName()) && !id.isEmpty()) {
                List<String> path = new ArrayList<>(prefix);
                path.add(id);
                List<String> options = new ArrayList<>();
                for (Node o = child.getFirstChild(); o != null; o = o.getNextSibling()) {
                    if (o instanceof Element opt && "option".equals(opt.getLocalName())) {
                        options.add(opt.getAttribute("value"));
                    }
                }
                Input input = new Input(String.join(".", path), child.getLocalName(), List.copyOf(options));
                Input seen = out.putIfAbsent(input.path(), input);
                if (seen != null && !seen.equals(input)) {
                    // The same path declared twice with other options: keep their union, in first-seen order.
                    Set<String> union = new TreeSet<>(seen.options());
                    List<String> merged = new ArrayList<>(seen.options());
                    for (String o : options) if (union.add(o)) merged.add(o);
                    out.put(input.path(), new Input(input.path(), seen.kind(), List.copyOf(merged)));
                }
                walk(child, path, out);
            } else {
                walk(child, prefix, out);
            }
        }
    }

    private static Document parse(byte[] script) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);
            return f.newDocumentBuilder().parse(new ByteArrayInputStream(script));
        } catch (ParserConfigurationException | SAXException e) {
            throw new IllegalArgumentException("not an archetype script: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
