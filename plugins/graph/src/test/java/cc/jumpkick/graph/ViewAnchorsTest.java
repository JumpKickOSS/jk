// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.codegraph.Anchors;
import cc.jumpkick.codegraph.CodeGraph;
import cc.jumpkick.codegraph.Confidence;
import cc.jumpkick.codegraph.Fragment;
import cc.jumpkick.codegraph.GraphEdge;
import cc.jumpkick.codegraph.GraphIds;
import cc.jumpkick.codegraph.GraphNode;
import cc.jumpkick.codegraph.GraphSnapshot;
import cc.jumpkick.codegraph.Linker;
import cc.jumpkick.codegraph.Modules;
import cc.jumpkick.codegraph.Relations;
import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Bean property anchors on spring-petclinic's owner pages: its Thymeleaf templates verbatim (at
 * 500158f, Apache-2.0, under src/test/resources/view-anchors/petclinic), and an OwnerController and
 * model in petclinic's shapes (under src/test/resources/view-anchors/java: a view constant, {@code @ModelAttribute("owner")}, {@code
 * ModelAndView.addObject(owner)}, {@code listOwners} added by a private helper, Owner extending
 * Person extending BaseEntity). Each path a template reads off the model is a use of the property
 * that declares it; renaming {@code Owner.telephone} leaves the three pages that read it stale.
 */
class ViewAnchorsTest {

    static final String PKG = "src/main/java/org/springframework/samples/petclinic/";
    static final String OWNER = PKG + "owner/Owner.java";
    static final String TEMPLATES = "src/main/resources/templates/";

    static Map<String, String> petclinic() throws IOException {
        Map<String, String> out = new TreeMap<>();
        Path dir = Path.of("src/test/resources/view-anchors/petclinic");
        PathUtil.forEachRegularFile(dir, (file, attrs) -> {
            String rel = dir.relativize(file).toString().replace('\\', '/');
            out.put(TEMPLATES + rel.substring(0, rel.length() - ".txt".length()), Files.readString(file));
        });
        Path java = Path.of("src/test/resources/view-anchors/java");
        PathUtil.forEachRegularFile(java, (file, attrs) -> {
            String rel = java.relativize(file).toString().replace('\\', '/');
            out.put(rel.substring(0, rel.length() - ".txt".length()), Files.readString(file));
        });
        return out;
    }

    /** Every bean anchor edge into a template or out of a class: relation, anchor, file:line per site, grade. */
    static List<String> beanEdges(RenameFixture ws) throws IOException {
        CodeGraph g;
        try (InputStream in = Files.newInputStream(ws.root.resolve("target/graph/graph.bin"))) {
            GraphSnapshot.Loaded loaded = GraphSnapshot.read(in);
            if (loaded == null) throw new AssertionError("no graph written");
            g = loaded.graph();
        }
        List<String> out = new ArrayList<>();
        for (GraphNode n : g.nodes()) {
            if (!Anchors.is(n) || !Anchors.BEAN.equals(Anchors.kindOf(n.id()))) continue;
            for (GraphEdge e : g.incident(n.id())) {
                if (!e.target().equals(n.id())) continue;
                for (String site : e.sites().isEmpty() ? List.of(e.sourceLocation()) : e.sites()) {
                    out.add(e.relation() + " " + n.id() + " "
                            + e.sourceFile().replace(TEMPLATES, "") + ":" + site.replace("L", "") + " "
                            + e.confidence().name().toLowerCase(Locale.ROOT));
                }
            }
        }
        out.sort(null);
        return out;
    }

    @Test
    void eachPathATemplateReadsOffItsControllersModelUsesThePropertyThatDeclaresIt(@TempDir Path dir)
            throws IOException {
        RenameFixture ws = new RenameFixture(dir, petclinic());
        AnchorEditsTest.refresh(ws);

        List<String> edges = beanEdges(ws);
        assertThat(edges.stream().filter(e -> e.startsWith("uses ")).toList())
                .containsExactlyInAnyOrder(
                        // ModelAndView("owners/ownerDetails").addObject(owner): th:object, th:each and their reads.
                        "uses bean:Person.firstName owners/ownerDetails.html:20 inferred",
                        "uses bean:Person.lastName owners/ownerDetails.html:20 inferred",
                        "uses bean:Owner.address owners/ownerDetails.html:24 inferred",
                        "uses bean:Owner.city owners/ownerDetails.html:28 inferred",
                        "uses bean:Owner.telephone owners/ownerDetails.html:32 inferred",
                        "uses bean:Owner.pets owners/ownerDetails.html:48 inferred",
                        "uses bean:NamedEntity.name owners/ownerDetails.html:52 inferred",
                        "uses bean:Pet.birthDate owners/ownerDetails.html:54 inferred",
                        "uses bean:Pet.type owners/ownerDetails.html:56 inferred",
                        "uses bean:Pet.visits owners/ownerDetails.html:67 inferred",
                        "uses bean:Visit.date owners/ownerDetails.html:68 inferred",
                        "uses bean:Visit.description owners/ownerDetails.html:69 inferred",
                        // The view constant and @ModelAttribute("owner"): the inputField fragment binds its 'name'.
                        "uses bean:Person.firstName owners/createOrUpdateOwnerForm.html:10 inferred",
                        "uses bean:Person.lastName owners/createOrUpdateOwnerForm.html:11 inferred",
                        "uses bean:Owner.address owners/createOrUpdateOwnerForm.html:12 inferred",
                        "uses bean:Owner.city owners/createOrUpdateOwnerForm.html:13 inferred",
                        "uses bean:Owner.telephone owners/createOrUpdateOwnerForm.html:14 inferred",
                        // listOwners, a List<Owner> the private helper adds.
                        "uses bean:Person.firstName owners/ownersList.html:22 inferred",
                        "uses bean:Person.lastName owners/ownersList.html:22 inferred",
                        "uses bean:Owner.address owners/ownersList.html:24 inferred",
                        "uses bean:Owner.city owners/ownersList.html:25 inferred",
                        "uses bean:Owner.telephone owners/ownersList.html:26 inferred",
                        "uses bean:Owner.pets owners/ownersList.html:27 inferred",
                        "uses bean:Person.lastName owners/findOwners.html:14 inferred");
        assertThat(edges)
                .contains("defines bean:Owner.telephone " + OWNER + ":13 extracted")
                .as("a fragment no handler renders, and a path built by preprocessing, link nothing")
                .noneMatch(e -> e.contains("fragments/") || e.startsWith("uses bean:BaseEntity.id"));
    }

    @Test
    void renamingAPropertyLeavesEveryPageThatReadsItStale(@TempDir Path dir) throws IOException {
        RenameFixture ws = new RenameFixture(dir, petclinic());
        AnchorEditsTest.refresh(ws);

        AnchorEditsTest.edit(ws, OWNER, "telephone", "phone");
        AnchorEditsTest.edit(ws, OWNER, "Telephone", "Phone");
        assertThat(ws.compile()).isNull();

        String t = "src/main/resources/templates/owners/";
        assertThat(AnchorEditsTest.refresh(ws))
                .containsExactlyInAnyOrder(
                        "must html bean Owner.telephone>Owner.phone " + t + "ownerDetails.html:32 after " + OWNER,
                        "must html bean Owner.telephone>Owner.phone " + t + "createOrUpdateOwnerForm.html:14 after "
                                + OWNER,
                        "must html bean Owner.telephone>Owner.phone " + t + "ownersList.html:26 after " + OWNER);

        AnchorEditsTest.edit(ws, OWNER, "private String city;", "private String town;");
        AnchorEditsTest.edit(ws, OWNER, "return this.city;", "return this.town;");
        assertThat(ws.compile()).isNull();
        assertThat(AnchorEditsTest.refresh(ws))
                .as("a field renamed behind its getter changes no bean name")
                .noneMatch(r -> r.contains("Owner.city"));
    }

    /**
     * A simple-layout module keeps its templates under {@code resources/templates}, Spring's {@code
     * classpath:/templates} as much as the conventional layout's: its pages are read the same way.
     */
    @Test
    void aSimpleLayoutModulesTemplatesArePairedWithTheirController(@TempDir Path dir) throws IOException {
        Map<String, String> files = new TreeMap<>();
        for (Map.Entry<String, String> e : petclinic().entrySet()) {
            String rel = e.getKey().replace(TEMPLATES, "resources/templates/").replace("src/main/java/", "src/");
            files.put(rel, e.getValue());
        }
        RenameFixture ws = new RenameFixture(dir, files, true);
        AnchorEditsTest.refresh(ws);

        assertThat(beanEdges(ws))
                .contains(
                        "uses bean:Owner.telephone resources/templates/owners/ownerDetails.html:32 inferred",
                        "uses bean:Owner.telephone resources/templates/owners/ownersList.html:26 inferred",
                        "defines bean:Owner.telephone src/org/springframework/samples/petclinic/owner/Owner.java:13"
                                + " extracted");
    }

    /**
     * jk's own tree holds Thymeleaf-shaped files under src/main/resources/templates (a bench fixture,
     * the scaffold templates) and no route a JVM handler defines: the pass stops there, reading no
     * template and no class, and the graph is the one it was given.
     */
    @Test
    void templatesWithNoHandlerReadNothing(@TempDir Path dir) {
        String page = "bench/agent-loop/smoke/fixture/" + TEMPLATES + "statement.html";
        String doc = "bench/tasks/openapi.yaml";
        CodeGraph g = Linker.link(List.of(new Fragment(
                List.of(
                        new GraphNode(GraphIds.file(page), "statement.html", "code", page, ""),
                        new GraphNode(GraphIds.file(doc), "openapi.yaml", "code", doc, ""),
                        Anchors.node(Anchors.ROUTE, "GET /statement")),
                List.of(Anchors.edge(
                        GraphIds.file(doc),
                        Anchors.ROUTE,
                        "GET /statement",
                        Relations.DEFINES,
                        Confidence.DECLARED,
                        doc,
                        3)))));
        List<String> read = new ArrayList<>();
        CodeGraph out = ViewAnchors.add(
                g,
                List.of(page, doc),
                Modules.NONE,
                rel -> {
                    read.add(rel);
                    return "";
                },
                new JsonAnchors.Classfiles(List.of(dir.resolve("absent")), Set.of()));
        assertThat(out).isSameAs(g);
        assertThat(read).isEmpty();
    }
}
