// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.rules.Allow;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.host.DomXml;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * {@code output}: assertions over what the build packaged — the sidecar POM's coordinates, the main
 * jar's entries and manifest. Reads the artefacts where {@link OutputArtifacts} says they are; a
 * module that produced none this build contributes nothing, and a rule that found no artefact at
 * all is {@code not-evaluated}, never clean.
 */
final class OutputEvaluator implements Evaluator {

    private static final String UNSPECIFIED = "unspecified";

    @Override
    public Evaluation evaluate(Rule rule, EvalContext ctx) throws IOException {
        TomlTable t = rule.table();
        TomlTable pom = t.getTable("pom");
        TomlTable jar = t.getTable("jar");
        TomlTable manifest = t.getTable("manifest");
        if (manifest == null && jar != null) manifest = jar.getTable("manifest");
        List<OutputArtifacts.Module> modules = OutputArtifacts.of(
                ctx.root(), ctx.modules(), ctx.rules().config().coverageReport());
        Map<Allow, Boolean> allowUsed = new LinkedHashMap<>();
        for (Allow a : rule.allow()) allowUsed.put(a, false);
        List<Observation> out = new ArrayList<>();
        long poms = 0;
        long jars = 0;
        TreeSet<String> published = new TreeSet<>();
        for (OutputArtifacts.Module m : modules) {
            Path p = m.existingPom();
            if (p != null) {
                String[] gav = coordinates(p);
                if (gav != null) published.add(gav[0] + ":" + gav[1]);
            }
        }
        for (OutputArtifacts.Module m : modules) {
            Allow allow = allowing(rule.allow(), m.module());
            if (allow != null) allowUsed.put(allow, true);
            if (pom != null) {
                Path p = m.existingPom();
                if (p != null) {
                    poms++;
                    if (allow == null) checkPom(pom, p, OutputArtifacts.rel(ctx.root(), p), published, out);
                }
            }
            if (jar != null || manifest != null) {
                Path j = m.existingJar();
                if (j != null) {
                    jars++;
                    if (allow == null) checkJar(jar, manifest, j, OutputArtifacts.rel(ctx.root(), j), out);
                }
            }
        }
        Map<String, Long> population = new LinkedHashMap<>();
        if (pom != null) population.put("poms", poms);
        if (jar != null || manifest != null) population.put("jars", jars);
        if (poms == 0 && jars == 0) {
            return Evaluation.notEvaluated("no artefact this build: nothing packaged under "
                    + (modules.isEmpty()
                            ? "the workspace"
                            : modules.get(0).jar().getParent()) + " — run jk build");
        }
        List<String> stale = new ArrayList<>();
        for (var e : allowUsed.entrySet())
            if (!e.getValue()) stale.add(e.getKey().in());
        if (!stale.isEmpty()) {
            return new Evaluation(
                    Outcome.STALE_ALLOW, population, out, "allow entries matched nothing: " + String.join(", ", stale));
        }
        return Evaluation.of(population, out);
    }

    // ---- pom ---------------------------------------------------------------------------------

    /** groupId, artifactId, version of the POM's own project, or {@code null} when it does not parse. */
    static String @Nullable [] coordinates(Path pomFile) {
        try {
            Element project = DomXml.parse(pomFile).getDocumentElement();
            return new String[] {
                DomXml.childText(project, "groupId"),
                DomXml.childText(project, "artifactId"),
                DomXml.childText(project, "version")
            };
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static void checkPom(
            TomlTable spec, Path pomFile, String rel, TreeSet<String> published, List<Observation> out) {
        boolean noUnspecified = Boolean.TRUE.equals(spec.getBoolean("no-unspecified"));
        List<String> groups = strings(spec.getArray("groups"));
        Document doc;
        try {
            doc = DomXml.parse(pomFile);
        } catch (IOException | RuntimeException e) {
            out.add(Observation.site(rel + " | unparseable", rel, 0, "the POM does not parse: " + e.getMessage()));
            return;
        }
        Element project = doc.getDocumentElement();
        String group = DomXml.childText(project, "groupId");
        String artifact = DomXml.childText(project, "artifactId");
        String version = DomXml.childText(project, "version");
        if (noUnspecified) {
            for (String[] f : new String[][] {{"groupId", group}, {"artifactId", artifact}, {"version", version}}) {
                if (f[1].isEmpty() || f[1].equals(UNSPECIFIED) || f[1].equals("jk")) {
                    out.add(Observation.site(
                            rel + " | " + f[0],
                            rel,
                            0,
                            "the POM's " + f[0] + " is `" + (f[1].isEmpty() ? "(missing)" : f[1])
                                    + "`; nobody can depend on that coordinate"));
                }
            }
        }
        if (!groups.isEmpty() && !group.isEmpty() && !groups.contains(group) && !group.equals(UNSPECIFIED)) {
            out.add(Observation.site(
                    rel + " | groupId " + group,
                    rel,
                    0,
                    "the POM publishes group `" + group + "`, which this build does not publish ("
                            + String.join(", ", groups) + ")"));
        }
        Element deps = DomXml.childElement(project, "dependencies");
        if (deps == null) return;
        for (Element d : DomXml.childElements(deps, "dependency")) {
            String dg = DomXml.childText(d, "groupId");
            String da = DomXml.childText(d, "artifactId");
            String dv = DomXml.childText(d, "version");
            if (noUnspecified && (dg.equals(UNSPECIFIED) || da.equals(UNSPECIFIED) || dv.equals(UNSPECIFIED))) {
                out.add(Observation.site(
                        rel + " | dependency " + dg + ":" + da,
                        rel,
                        0,
                        "the POM depends on `" + dg + ":" + da + ":" + dv + "`, an unspecified coordinate"));
                continue;
            }
            if (groups.contains(dg) && !published.contains(dg + ":" + da)) {
                out.add(Observation.site(
                        rel + " | dependency " + dg + ":" + da,
                        rel,
                        0,
                        "the POM depends on `" + dg + ":" + da
                                + "`, which is in a group this build publishes and is not an artifact it packages"));
            }
        }
    }

    // ---- jar ---------------------------------------------------------------------------------

    private static void checkJar(
            @Nullable TomlTable spec,
            @Nullable TomlTable manifestSpec,
            Path jarFile,
            String rel,
            List<Observation> out) {
        List<String> forbid = spec == null ? List.of() : strings(spec.getArray("forbid-entries"));
        List<String> require = spec == null ? List.of() : strings(spec.getArray("require-entries"));
        try (JarFile jar = new JarFile(jarFile.toFile(), false)) {
            List<String> names = new ArrayList<>();
            Enumeration<? extends ZipEntry> e = jar.entries();
            while (e.hasMoreElements()) names.add(e.nextElement().getName());
            for (String glob : forbid) {
                for (String n : names) {
                    if (Rule.globMatches(glob, n))
                        out.add(Observation.site(
                                rel + " | " + n, rel, 0, "entry `" + n + "` matches forbid-entries `" + glob + "`"));
                }
            }
            for (String glob : require) {
                boolean found = false;
                for (String n : names) if (Rule.globMatches(glob, n)) found = true;
                if (!found)
                    out.add(Observation.site(
                            rel + " | missing " + glob, rel, 0, "no entry matches require-entries `" + glob + "`"));
            }
            if (manifestSpec != null) {
                Manifest mf = jar.getManifest();
                Attributes main = mf == null ? new Attributes() : mf.getMainAttributes();
                for (String key : manifestSpec.keySet()) {
                    String want = String.valueOf(manifestSpec.get(key));
                    String have = main.getValue(key);
                    if (have == null) {
                        out.add(Observation.site(
                                rel + " | manifest " + key, rel, 0, "the manifest has no `" + key + "` attribute"));
                    } else if (!have.equals(want)) {
                        out.add(Observation.site(
                                rel + " | manifest " + key,
                                rel,
                                0,
                                "the manifest's `" + key + "` is `" + have + "`, not `" + want + "`"));
                    }
                }
            }
        } catch (IOException ex) {
            out.add(Observation.site(rel + " | unreadable", rel, 0, "the jar does not open: " + ex.getMessage()));
        }
    }

    private static List<String> strings(@Nullable TomlArray a) {
        List<String> out = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.size(); i++) out.add(String.valueOf(a.get(i)));
        return out;
    }

    private static @Nullable Allow allowing(List<Allow> allow, String module) {
        for (Allow a : allow) if (a.in().equals(module) || Rule.globMatches(a.in(), module)) return a;
        return null;
    }
}
