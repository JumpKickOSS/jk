// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import cc.jumpkick.host.DomXml;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * AGP-style AAR {@code res/} merge before aapt2: classpath order, earlier wins; module resources
 * stay the {@code -R} overlay. File resources first-writer-wins; values merge by
 * {@code (tag, type, name)} into one {@code values.xml} per config.
 */
final class ResourceMerger {

    private static final String XMLNS = "xmlns:";

    private ResourceMerger() {}

    /**
     * Merge every AAR's {@code res/} into {@code out}. Returns {@code out}, or null when no
     * dependency carries resources (link then has no dep input at all).
     */
    static @Nullable Path mergeDepRes(List<AndroidDeps.Aar> aars, Path out) throws Exception {
        // config dir → resource key → element (first wins); plus the merged xmlns decls, prefix → URI.
        Map<String, Map<String, Element>> valuesByConfig = new LinkedHashMap<>();
        Map<String, String> xmlns = new TreeMap<>();
        boolean any = false;

        for (AndroidDeps.Aar aar : aars) {
            if (!aar.hasRes()) continue;
            any = true;
            try (var configs = Files.list(aar.res())) {
                for (Path configDir : (Iterable<Path>) configs.sorted()::iterator) {
                    if (!Files.isDirectory(configDir)) continue;
                    String config = configDir.getFileName().toString();
                    if (config.equals("values") || config.startsWith("values-")) {
                        mergeValuesDir(
                                configDir, valuesByConfig.computeIfAbsent(config, k -> new LinkedHashMap<>()), xmlns);
                    } else {
                        copyFirstWins(configDir, out.resolve(config));
                    }
                }
            }
        }
        if (!any) return null;

        for (var config : valuesByConfig.entrySet()) {
            writeMergedValues(
                    config.getValue(), xmlns, out.resolve(config.getKey()).resolve("values.xml"));
        }
        Files.createDirectories(out); // an all-values closure still needs the tree root to exist
        return out;
    }

    private static void copyFirstWins(Path configDir, Path outDir) throws IOException {
        // outDir is the method's parameter and cannot change, so creating it inside the loop paid a
        // CreateDirectoryW-returning-ERROR_ALREADY_EXISTS per file on Windows.
        Files.createDirectories(outDir);
        try (var files = Files.list(configDir)) {
            for (Path file : (Iterable<Path>) files.sorted()::iterator) {
                if (!Files.isRegularFile(file)) continue;
                Path target = outDir.resolve(file.getFileName().toString());
                if (Files.exists(target)) continue; // earlier dependency won
                Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    /** An AAR is a downloaded third-party library, so {@link DomXml} rejects a DOCTYPE in its resources. */
    private static void mergeValuesDir(Path valuesDir, Map<String, Element> merged, Map<String, String> xmlns)
            throws Exception {
        try (var files = Files.list(valuesDir)) {
            for (Path file : (Iterable<Path>) files.sorted()::iterator) {
                if (!Files.isRegularFile(file) || !file.toString().endsWith(".xml")) continue;
                Document doc = DomXml.parse(file);
                Element root = doc.getDocumentElement();
                if (!"resources".equals(root.getTagName())) continue;
                Map<String, String> renames = new HashMap<>();
                var attrs = root.getAttributes();
                for (int a = 0; a < attrs.getLength(); a++) {
                    Node attr = attrs.item(a);
                    if (!attr.getNodeName().startsWith(XMLNS)) continue;
                    String prefix = attr.getNodeName().substring(XMLNS.length());
                    String mergedPrefix = mergedPrefix(prefix, attr.getNodeValue(), xmlns);
                    if (!mergedPrefix.equals(prefix)) renames.put(prefix, mergedPrefix);
                }
                NodeList children = root.getChildNodes();
                for (int c = 0; c < children.getLength(); c++) {
                    if (!(children.item(c) instanceof Element el)) continue;
                    if (!renames.isEmpty()) el = renamePrefixes(el, renames);
                    merged.putIfAbsent(keyOf(el), el);
                }
            }
        }
    }

    /**
     * The prefix the merged file binds {@code uri} to, declaring it in {@code xmlns} on first use.
     * Generated prefixes such as {@code ns1} mean different URIs in different libraries, so a
     * prefix another file already bound elsewhere gets a fresh one.
     */
    private static String mergedPrefix(String prefix, String uri, Map<String, String> xmlns) {
        if (uri.equals(xmlns.get(prefix))) return prefix;
        for (var decl : xmlns.entrySet()) {
            if (decl.getValue().equals(uri)) return decl.getKey();
        }
        String fresh = prefix;
        for (int n = 1; xmlns.containsKey(fresh); n++) fresh = "ns" + n;
        xmlns.put(fresh, uri);
        return fresh;
    }

    /**
     * {@code el} with every element and attribute prefix in {@code renames} rewritten, below as
     * well: a renamed element is a new one holding the old one's attributes and children. A prefix
     * an element declares again is its own from there down and stays.
     */
    private static Element renamePrefixes(Element el, Map<String, String> renames) {
        Map<String, String> scoped = new HashMap<>(renames);
        List<Attr> attrs = new ArrayList<>();
        var map = el.getAttributes();
        for (int a = 0; a < map.getLength(); a++) attrs.add((Attr) map.item(a));
        for (Attr attr : attrs) {
            if (attr.getName().startsWith(XMLNS)) scoped.remove(attr.getName().substring(XMLNS.length()));
        }
        for (Attr attr : attrs) {
            String renamed = renamed(attr.getName(), scoped);
            if (renamed == null) continue;
            el.removeAttributeNode(attr);
            el.setAttribute(renamed, attr.getValue());
        }
        for (Node child = el.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (!(child instanceof Element childEl)) continue;
            Element renamedChild = renamePrefixes(childEl, scoped);
            if (renamedChild != childEl) el.replaceChild(renamedChild, childEl);
            child = renamedChild;
        }
        String renamed = renamed(el.getTagName(), scoped);
        if (renamed == null) return el;
        Element copy = el.getOwnerDocument().createElement(renamed);
        var kept = el.getAttributes();
        for (int a = 0; a < kept.getLength(); a++)
            copy.setAttribute(kept.item(a).getNodeName(), kept.item(a).getNodeValue());
        while (el.getFirstChild() != null) copy.appendChild(el.getFirstChild());
        return copy;
    }

    /** {@code name} under its prefix's new spelling, or null when its prefix is not renamed. */
    private static @Nullable String renamed(String name, Map<String, String> renames) {
        int colon = name.indexOf(':');
        if (colon < 0) return null;
        String to = renames.get(name.substring(0, colon));
        return to == null ? null : to + name.substring(colon);
    }

    /** {@code tag/type-attr/name} — {@code <item type="id" name="x">} keys apart from strings. */
    private static String keyOf(Element el) {
        return el.getTagName() + '/' + el.getAttribute("type") + '/' + el.getAttribute("name");
    }

    private static void writeMergedValues(Map<String, Element> entries, Map<String, String> xmlns, Path out)
            throws Exception {
        Document doc = DomXml.newDocument();
        Element root = doc.createElement("resources");
        for (var decl : xmlns.entrySet()) {
            root.setAttribute(XMLNS + decl.getKey(), decl.getValue());
        }
        doc.appendChild(root);
        // One resource per line, written by hand: the serializer's indent would also indent inside
        // a <string> with markup in it, and that whitespace is part of the string's value.
        for (Element el : entries.values()) {
            root.appendChild(doc.createTextNode("\n    "));
            root.appendChild(doc.importNode(el, true));
        }
        root.appendChild(doc.createTextNode("\n"));
        Files.createDirectories(out.getParent());
        Transformer tf = TransformerFactory.newInstance().newTransformer();
        StringWriter sw = new StringWriter();
        tf.transform(new DOMSource(doc), new StreamResult(sw));
        Files.writeString(out, sw.toString());
    }
}
