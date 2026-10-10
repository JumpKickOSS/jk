// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.intellij;

import cc.jumpkick.host.DomXml;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Element;

/**
 * The bundled plugins an IDE must load for the named ones to load. A plugin requires what its
 * descriptor names in a required {@code <depends>} or in {@code <dependencies>} — a plugin id, an
 * alias some plugin declares with {@code <module value>}, or a module — and its required content
 * modules. A module requires what its own descriptor names, and the plugin that ships it when that
 * is a bundled plugin rather than the platform. The closure follows both, transitively; optional
 * dependencies and optional content modules are not followed, as the IDE loads without them.
 */
final class PluginClosure {

    private final Path ide;
    private final ProductInfo info;
    private final Map<String, Element> plugins = new HashMap<>();
    private final Map<String, String> aliasOwners = new HashMap<>();
    private final Map<String, Element> inlineModules = new HashMap<>();

    private PluginClosure(Path ide, ProductInfo info) throws IOException {
        this.ide = ide;
        this.info = info;
        for (String id : info.plugins().keySet()) {
            Element root = pluginDescriptor(id);
            if (root == null) continue;
            plugins.put(id, root);
            for (Element alias : DomXml.childElements(root, "module")) {
                String value = alias.getAttribute("value");
                if (!value.isEmpty()) aliasOwners.put(value, id);
            }
            for (Element content : DomXml.childElements(root, "content")) {
                for (Element module : DomXml.childElements(content, "module")) {
                    String inline = module.getTextContent().trim();
                    if (!inline.isEmpty()) inlineModules.put(module.getAttribute("name"), parse(inline));
                }
            }
        }
    }

    /** {@code seeds} and every bundled plugin they require, seeds first; the platform itself is never one. */
    static List<String> of(Path ide, ProductInfo info, List<String> seeds) throws IOException {
        return new PluginClosure(ide, info).closure(seeds);
    }

    private record Need(boolean plugin, String name) {}

    private List<String> closure(List<String> seeds) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        Set<Need> seen = new HashSet<>();
        Deque<Need> work = new ArrayDeque<>();
        for (String seed : seeds) work.add(new Need(true, seed));
        while (!work.isEmpty()) {
            Need need = work.removeFirst();
            if (!seen.add(need)) continue;
            if (need.plugin()) {
                String id = info.plugins().containsKey(need.name()) ? need.name() : aliasOwners.get(need.name());
                if (id == null) continue; // an alias the platform itself provides
                if (info.pluginDir(id) != null) out.add(id);
                Element root = plugins.get(id);
                if (root == null) continue;
                requirements(root, work);
                for (Element content : DomXml.childElements(root, "content")) {
                    for (Element module : DomXml.childElements(content, "module")) {
                        if (required(module)) work.add(new Need(false, module.getAttribute("name")));
                    }
                }
            } else {
                // An embedded module has no layout entry of its own: its plugin is already in, and
                // its descriptor is inline in the plugin's.
                String jar = info.modules().get(need.name());
                String dir = jar == null ? null : ProductInfo.directoryOf(jar);
                String owner = dir == null ? null : info.pluginIn(dir);
                if (owner != null) work.add(new Need(true, owner));
                Element root = moduleDescriptor(need.name(), jar);
                if (root != null) requirements(root, work);
            }
        }
        return List.copyOf(out);
    }

    /** Every plugin id, alias and module a descriptor requires, queued on {@code work}. */
    private static void requirements(Element root, Deque<Need> work) {
        for (Element depends : DomXml.childElements(root, "depends")) {
            if (Boolean.parseBoolean(depends.getAttribute("optional"))) continue;
            String id = depends.getTextContent().trim();
            if (!id.isEmpty()) work.add(new Need(true, id));
        }
        List<Need> named = new ArrayList<>();
        for (Element dependencies : DomXml.childElements(root, "dependencies")) {
            for (Element dependency : DomXml.childElements(dependencies)) {
                boolean plugin = dependency.getNodeName().equals("plugin");
                String name = dependency.getAttribute(plugin ? "id" : "name");
                if (!name.isEmpty()) named.add(new Need(plugin, name));
            }
        }
        work.addAll(named);
    }

    /** A content module the IDE refuses to load its plugin without. */
    private static boolean required(Element module) {
        String loading = module.getAttribute("loading");
        return loading.equals("embedded") || loading.equals("required") || module.hasAttribute("required-if-available");
    }

    /** A module's descriptor: inline in its plugin's, or {@code <name>.xml} in its jar. */
    private @Nullable Element moduleDescriptor(String name, @Nullable String jar) throws IOException {
        Element inline = inlineModules.get(name);
        if (inline != null) return inline;
        return jar == null ? null : entry(jar, name + ".xml");
    }

    private @Nullable Element pluginDescriptor(String id) throws IOException {
        for (String jar : info.plugins().getOrDefault(id, List.of())) {
            Element root = entry(jar, "META-INF/plugin.xml");
            if (root != null) return root;
        }
        return null;
    }

    private @Nullable Element entry(String jar, String name) throws IOException {
        Path file = ide.resolve(jar);
        if (!Files.isRegularFile(file)) return null;
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry entry = zip.getEntry(name);
            if (entry == null) return null;
            try (InputStream in = zip.getInputStream(entry)) {
                return DomXml.parse(in).getDocumentElement();
            }
        }
    }

    private static Element parse(String xml) throws IOException {
        return DomXml.parse(xml).getDocumentElement();
    }
}
