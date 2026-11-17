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
import cc.jumpkick.codegraph.Relations;
import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.attribute.SourceFileAttribute;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JSON property anchors on a small Spring service with its clients: two controllers whose bodies
 * share the property name {@code name} (an owner's, a pet's and a vet's), a TypeScript client, a
 * smoke script piping curl into jq, a test reading JSONPath, a JSON fixture, a hand-written OpenAPI
 * file and one a springdoc plugin writes. Each client mention is linked only where it is tied to a
 * route or a class, graded; an untied {@code .name} is linked nowhere; the generated document is
 * never read. Renaming a property by its {@code @JsonProperty} leaves every tied place stale; an edit
 * that changes no JSON name leaves nothing.
 */
class JsonAnchorsTest {

    static final String API = "src/main/java/com/acme/clinic/api/";
    static final String OWNER_DTO = API + "OwnerDto.java";
    static final String CLIENT = "client/src/api.ts";

    /**
     * The fixture's files, from {@code src/test/resources/json-anchors/clinic}, where each is stored with a
     * {@code .txt} suffix so nothing in this repository reads it as the source it stands for.
     */
    static Map<String, String> clinic() throws IOException {
        Path dir = Path.of("src/test/resources/json-anchors/clinic");
        Map<String, String> out = new TreeMap<>();
        PathUtil.forEachRegularFile(dir, (file, attrs) -> {
            String rel = dir.relativize(file).toString().replace('\\', '/');
            out.put(rel.substring(0, rel.length() - ".txt".length()), Files.readString(file));
        });
        return out;
    }

    /** Every JSON anchor edge of the graph the last refresh wrote: relation, anchor, file:lines, grade. */
    static List<String> jsonEdges(RenameFixture ws) throws IOException {
        CodeGraph g;
        try (InputStream in = Files.newInputStream(ws.root.resolve("target/graph/graph.bin"))) {
            GraphSnapshot.Loaded loaded = GraphSnapshot.read(in);
            if (loaded == null) throw new AssertionError("no graph written");
            g = loaded.graph();
        }
        List<String> out = new ArrayList<>();
        for (GraphNode n : g.nodes()) {
            if (!Anchors.is(n) || !Anchors.JSON.equals(Anchors.kindOf(n.id()))) continue;
            for (GraphEdge e : g.incident(n.id())) {
                if (!e.target().equals(n.id())) continue;
                List<String> sites = e.sites().isEmpty() ? List.of(e.sourceLocation()) : e.sites();
                String lines = String.join(
                        ",",
                        sites.stream()
                                .map(l -> l.replace("L", ""))
                                .sorted(Comparator.comparingInt(Integer::parseInt))
                                .toList());
                out.add(e.relation() + " " + n.id() + " " + e.sourceFile() + ":" + lines + " "
                        + e.confidence().name().toLowerCase(Locale.ROOT));
            }
        }
        out.sort(null);
        return out;
    }

    @Test
    void eachClientMentionIsLinkedOnlyWhereARouteOrAClassTiesIt(@TempDir Path dir) throws IOException {
        RenameFixture ws = new RenameFixture(dir, clinic());
        AnchorEditsTest.refresh(ws);

        List<String> uses =
                jsonEdges(ws).stream().filter(e -> e.startsWith("uses ")).toList();
        assertThat(uses)
                .containsExactlyInAnyOrder(
                        // The client: interfaces named for the DTOs, reads in the function that requests the route.
                        "uses json:OwnerDto.id client/src/api.ts:2 inferred",
                        "uses json:OwnerDto.name client/src/api.ts:3 inferred",
                        "uses json:OwnerDto.birth_date client/src/api.ts:4,17 inferred",
                        "uses json:OwnerDto.pets client/src/api.ts:5 inferred",
                        "uses json:PetDto.id client/src/api.ts:9 inferred",
                        "uses json:PetDto.name client/src/api.ts:10 inferred",
                        "uses json:PetDto.type client/src/api.ts:11 inferred",
                        "uses json:NewOwner.first_name client/src/api.ts:24 inferred",
                        "uses json:NewOwner.last_name client/src/api.ts:24 inferred",
                        "uses json:VetDto.name client/src/api.ts:32 inferred",
                        // jq on the pipeline that requests the route; `.id` of POST /api/owners is an owner's or a
                        // pet's.
                        "uses json:OwnerDto.birth_date scripts/smoke.sh:3 inferred",
                        "uses json:VetDto.name scripts/smoke.sh:4 inferred",
                        "uses json:OwnerDto.id scripts/smoke.sh:5 ambiguous",
                        "uses json:PetDto.id scripts/smoke.sh:5 ambiguous",
                        // JSONPath in the test method that requests it.
                        "uses json:OwnerDto.birth_date src/test/java/com/acme/clinic/api/OwnerApiTests.java:6 inferred",
                        "uses json:OwnerDto.pets src/test/java/com/acme/clinic/api/OwnerApiTests.java:6 inferred",
                        "uses json:OwnerDto.name src/test/java/com/acme/clinic/api/OwnerApiTests.java:6 ambiguous",
                        "uses json:PetDto.name src/test/java/com/acme/clinic/api/OwnerApiTests.java:6 ambiguous",
                        "uses json:OwnerDto.id src/test/java/com/acme/clinic/api/OwnerApiTests.java:10 ambiguous",
                        "uses json:PetDto.id src/test/java/com/acme/clinic/api/OwnerApiTests.java:10 ambiguous",
                        // The fixture a requesting file names.
                        "uses json:NewOwner.first_name src/test/resources/new-owner.json:2 inferred",
                        "uses json:NewOwner.last_name src/test/resources/new-owner.json:3 inferred",
                        // The hand-written OpenAPI file: a schema named for the DTO, an inline body under its path.
                        "uses json:OwnerDto.id docs/openapi.yaml:30 inferred",
                        "uses json:OwnerDto.birth_date docs/openapi.yaml:32 inferred",
                        "uses json:NewOwner.first_name docs/openapi.yaml:23 inferred");
        assertThat(jsonEdges(ws))
                .as("properties Jackson does not write are not anchors; nor is the generated document read")
                .noneMatch(e -> e.contains("PetDto.chip")
                        || e.contains("PetDto.secret")
                        || e.contains("birthDate")
                        || e.contains("firstName")
                        || e.contains("api/openapi.json")
                        || e.contains("util.ts")
                        || e.contains("unused.json"))
                .contains("defines json:OwnerDto.birth_date " + OWNER_DTO + ":10 extracted");
    }

    @Test
    void aPropertyRenamedOnTheJavaSideLeavesEveryTiedPlaceStale(@TempDir Path dir) throws IOException {
        RenameFixture ws = new RenameFixture(dir, clinic());
        AnchorEditsTest.refresh(ws);

        AnchorEditsTest.edit(ws, OWNER_DTO, "@JsonProperty(\"birth_date\")", "@JsonProperty(\"born\")");
        assertThat(ws.compile()).isNull();

        assertThat(AnchorEditsTest.refresh(ws))
                .containsExactlyInAnyOrder(
                        "must ts json OwnerDto.birth_date>OwnerDto.born " + CLIENT + ":4 after " + OWNER_DTO,
                        "must ts json OwnerDto.birth_date>OwnerDto.born " + CLIENT + ":17 after " + OWNER_DTO,
                        "must sh json OwnerDto.birth_date>OwnerDto.born scripts/smoke.sh:3 after " + OWNER_DTO,
                        "must java json OwnerDto.birth_date>OwnerDto.born"
                                + " src/test/java/com/acme/clinic/api/OwnerApiTests.java:6 after " + OWNER_DTO,
                        "must yaml json OwnerDto.birth_date>OwnerDto.born docs/openapi.yaml:32 after " + OWNER_DTO);

        AnchorEditsTest.edit(ws, CLIENT, "birth_date", "born");
        AnchorEditsTest.edit(ws, "scripts/smoke.sh", "birth_date", "born");
        assertThat(AnchorEditsTest.refresh(ws))
                .as("the places fixed leave; the rest stay")
                .noneMatch(r -> r.contains(CLIENT) || r.contains("smoke.sh"))
                .hasSize(2);
    }

    @Test
    void anEditThatChangesNoJsonNameLeavesNothing(@TempDir Path dir) throws IOException {
        RenameFixture ws = new RenameFixture(dir, clinic());
        AnchorEditsTest.refresh(ws);

        // The field behind a getter renamed: Jackson still writes "type".
        AnchorEditsTest.edit(ws, API + "PetDto.java", "private String type;", "private String kind;");
        AnchorEditsTest.edit(ws, API + "PetDto.java", "return type;", "return kind;");
        // Validation and a handler's body.
        AnchorEditsTest.edit(
                ws, API + "NewOwner.java", "    public String lastName;", "    @NotBlank\n    public String lastName;");
        AnchorEditsTest.edit(
                ws,
                API + "OwnerController.java",
                "return List.of();",
                "return List.of(new OwnerDto(1, \"a\", null, List.of()));");
        assertThat(ws.compile()).isNull();

        assertThat(AnchorEditsTest.refresh(ws)).isEmpty();
        assertThat(jsonEdges(ws)).contains("uses json:PetDto.type client/src/api.ts:11 inferred");
    }

    /**
     * No route returns or reads a class of the workspace (a view controller, a route returning a
     * String): no JSON anchor, though a client reads {@code .name}.
     */
    @Test
    void aWorkspaceWhoseRoutesCarryNoClassOfItsOwnHasNoJsonAnchor(@TempDir Path dir) throws IOException {
        Map<String, String> files = clinic();
        files.keySet().removeIf(rel -> rel.startsWith(API));
        files.put(API + "PageController.java", """
                package com.acme.clinic.api;

                import org.springframework.stereotype.Controller;
                import org.springframework.web.bind.annotation.GetMapping;

                @Controller
                public class PageController {

                    @GetMapping("/owners/{id}")
                    public String show() {
                        return "owners/show";
                    }

                    @GetMapping("/api/owners/{id}")
                    public Page owner() {
                        return new Page();
                    }

                    public static class Page {
                        public String name;
                    }
                }
                """);
        files.remove("src/test/java/com/acme/clinic/api/OwnerApiTests.java");
        RenameFixture ws = new RenameFixture(dir, files);
        AnchorEditsTest.refresh(ws);

        assertThat(jsonEdges(ws)).isEmpty();
    }

    /**
     * Two classes of one simple name, each a route's body: each property is keyed by its class's
     * qualified name, and a member of the client type named for both links to each as a judgment
     * call unless only one of them carries it.
     */
    @Test
    void twoBodyClassesOfOneSimpleNameAreKeptApartByTheirPackages(@TempDir Path dir) throws IOException {
        Map<String, String> files = clinic();
        files.put("src/main/java/com/acme/clinic/admin/OwnerDto.java", """
                package com.acme.clinic.admin;

                public record OwnerDto(int id, String email) {}
                """);
        files.put("src/main/java/com/acme/clinic/admin/AdminController.java", """
                package com.acme.clinic.admin;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class AdminController {

                    @GetMapping("/api/admin/owners/{id}")
                    public OwnerDto owner() {
                        return null;
                    }
                }
                """);
        files.put("client/src/admin.ts", """
                export async function ownerEmail(id: number): Promise<string> {
                  const res = await fetch(`/api/admin/owners/${id}`);
                  const owner = await res.json();
                  return owner.email;
                }
                """);
        RenameFixture ws = new RenameFixture(dir, files);
        AnchorEditsTest.refresh(ws);

        List<String> edges = jsonEdges(ws);
        assertThat(edges)
                .contains(
                        "uses json:com.acme.clinic.admin.OwnerDto.email client/src/admin.ts:4 inferred",
                        "uses json:com.acme.clinic.admin.OwnerDto.id client/src/api.ts:2 ambiguous",
                        "uses json:com.acme.clinic.api.OwnerDto.id client/src/api.ts:2 ambiguous",
                        "uses json:com.acme.clinic.api.OwnerDto.name client/src/api.ts:3 inferred",
                        "defines json:com.acme.clinic.admin.OwnerDto.email src/main/java/com/acme/clinic/admin/OwnerDto.java:3"
                                + " extracted")
                .noneMatch(e -> e.contains(" json:OwnerDto."))
                .as("a class of a name no other body class has keeps its simple name")
                .contains("uses json:PetDto.type client/src/api.ts:11 inferred");
    }

    /**
     * A Kotlin controller and data class: the route and the properties come from the class files
     * kotlinc wrote (here javac's, in kotlinc's shape: private fields, getters, the annotation on the
     * constructor parameter, {@code isActive}, its {@code SourceFile} the Kotlin file), the
     * definition's line from the Kotlin source.
     */
    @Test
    void aKotlinControllersDataClassIsReadFromItsClassFiles(@TempDir Path dir) throws IOException {
        Map<String, String> files = clinic();
        String kt = "src/main/java/com/acme/clinic/kt/";
        files.put("src/main/java/kotlin/Metadata.java", "package kotlin;\n\npublic @interface Metadata {}\n");
        files.put(kt + "OwnerCard.java", """
                package com.acme.clinic.kt;

                import com.fasterxml.jackson.annotation.JsonProperty;

                @kotlin.Metadata
                public final class OwnerCard {
                    private final String firstName;
                    private final String telephone;
                    private final boolean isActive;

                    public OwnerCard(String firstName, @JsonProperty("tel") String telephone, boolean isActive) {
                        this.firstName = firstName;
                        this.telephone = telephone;
                        this.isActive = isActive;
                    }

                    public final String getFirstName() { return firstName; }
                    public final String getTelephone() { return telephone; }
                    public final boolean isActive() { return isActive; }
                    public final String component1() { return firstName; }
                }
                """);
        files.put(kt + "CardController.java", """
                package com.acme.clinic.kt;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;

                @kotlin.Metadata
                @RestController
                @RequestMapping("/api/kt")
                public final class CardController {
                    @GetMapping("/cards/{id}")
                    public final OwnerCard card(@PathVariable int id) { return null; }
                }
                """);
        files.put("client/src/cards.ts", """
                export interface OwnerCard {
                  firstName: string;
                  tel: string;
                  isActive: boolean;
                }

                export async function phone(id: number): Promise<string> {
                  const res = await fetch(`/api/kt/cards/${id}`);
                  const card = await res.json();
                  return card.tel;
                }
                """);
        RenameFixture ws = new RenameFixture(dir, files);
        // What kotlinc writes: the classes name Cards.kt as their source, and the tree holds the Kotlin file.
        Path classes = ws.root.resolve("target/classes/com/acme/clinic/kt");
        for (String c : List.of("OwnerCard", "CardController")) {
            Path f = classes.resolve(c + ".class");
            ClassModel cm = ClassFile.of().parse(Files.readAllBytes(f));
            Files.write(
                    f,
                    ClassFile.of()
                            .transformClass(
                                    cm,
                                    ClassTransform.dropping(e -> e instanceof SourceFileAttribute)
                                            .andThen(ClassTransform.endHandler(
                                                    b -> b.with(SourceFileAttribute.of("Cards.kt"))))));
            Files.delete(ws.root.resolve(kt + c + ".java"));
        }
        String kotlin = "src/main/kotlin/com/acme/clinic/kt/Cards.kt";
        Files.createDirectories(ws.root.resolve(kotlin).getParent());
        Files.writeString(ws.root.resolve(kotlin), """
                package com.acme.clinic.kt

                import com.fasterxml.jackson.annotation.JsonProperty
                import org.springframework.web.bind.annotation.GetMapping
                import org.springframework.web.bind.annotation.PathVariable
                import org.springframework.web.bind.annotation.RequestMapping
                import org.springframework.web.bind.annotation.RestController

                data class OwnerCard(
                    val firstName: String,
                    @JsonProperty("tel") val telephone: String,
                    val isActive: Boolean,
                )

                @RestController
                @RequestMapping("/api/kt")
                class CardController {
                    @GetMapping("/cards/{id}")
                    fun card(@PathVariable id: Int): OwnerCard = OwnerCard("a", "b", true)
                }
                """);
        AnchorEditsTest.refresh(ws);

        assertThat(jsonEdges(ws).stream().filter(e -> e.contains("OwnerCard")).toList())
                .containsExactlyInAnyOrder(
                        "defines json:OwnerCard.firstName " + kotlin + ":10 extracted",
                        "defines json:OwnerCard.tel " + kotlin + ":11 extracted",
                        "defines json:OwnerCard.isActive " + kotlin + ":12 extracted",
                        "uses json:OwnerCard.firstName client/src/cards.ts:2 inferred",
                        "uses json:OwnerCard.tel client/src/cards.ts:3,10 inferred",
                        "uses json:OwnerCard.isActive client/src/cards.ts:4 inferred");
    }

    /**
     * jhipster-sample-app's Cypress spec for bank accounts (at 6b000b5d23a36c45e01472471b84a44fa2464044,
     * Apache-2.0, verbatim): one {@code describe} holds every request, through {@code cy.intercept} and
     * a custom command's {@code {method, url, body}}. The sample it posts, {@code { name, balance }}
     * at line 17, is tied to the POST it is sent with though it sits outside that function; the
     * options objects' keys and the reads in other {@code it} blocks are not. The keys of an object that is
     * neither sent nor compared (an analytics call's) are judgment calls, never places that must change.
     */
    @Test
    void aSpecsPostedSampleIsTiedToItsRequestAndAnOptionsKeyIsAJudgmentCall(@TempDir Path dir) throws IOException {
        Map<String, String> files = clinic();
        files.put("src/main/java/org/springframework/web/bind/annotation/DeleteMapping.java", """
                package org.springframework.web.bind.annotation;

                public @interface DeleteMapping {
                    String[] value() default {};
                }
                """);
        String jh = "src/main/java/io/github/jhipster/sample/";
        files.put(jh + "domain/BankAccount.java", """
                package io.github.jhipster.sample.domain;

                import java.math.BigDecimal;

                public class BankAccount {
                    private Long id;
                    private String name;
                    private BigDecimal balance;

                    public Long getId() { return id; }
                    public String getName() { return name; }
                    public BigDecimal getBalance() { return balance; }
                }
                """);
        files.put(jh + "web/rest/BankAccountResource.java", """
                package io.github.jhipster.sample.web.rest;

                import io.github.jhipster.sample.domain.BankAccount;
                import java.util.List;
                import org.springframework.http.ResponseEntity;
                import org.springframework.web.bind.annotation.DeleteMapping;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestBody;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping("/api/bank-accounts")
                public class BankAccountResource {
                    @PostMapping("")
                    public ResponseEntity<BankAccount> createBankAccount(@RequestBody BankAccount bankAccount) { return null; }

                    @GetMapping("")
                    public List<BankAccount> getAllBankAccounts() { return List.of(); }

                    @DeleteMapping("/{id}")
                    public ResponseEntity<Void> deleteBankAccount(@PathVariable Long id) { return null; }
                }
                """);
        String cy = "src/test/javascript/cypress/e2e/entity/bank-account.cy.ts";
        files.put(cy, Files.readString(Path.of("src/test/resources/json-anchors/jhipster/bank-account.cy.ts.txt")));
        files.put("client/src/accounts.ts", """
                export async function search(q: string) {
                  const res = await api.get('/api/bank-accounts', { params: { q } });
                  analytics.track({ name: 'search', id: q });
                  return res.data.map((a: { balance: number }) => a.balance);
                }
                """);
        RenameFixture ws = new RenameFixture(dir, files);
        AnchorEditsTest.refresh(ws);

        assertThat(jsonEdges(ws).stream()
                        .filter(e -> e.contains(cy) || e.contains("accounts.ts"))
                        .toList())
                .containsExactlyInAnyOrder(
                        "uses json:BankAccount.name " + cy + ":17 inferred",
                        "uses json:BankAccount.balance " + cy + ":17 inferred",
                        // bankAccount is the POST's response, read in a function that requests only DELETE.
                        "uses json:BankAccount.id " + cy + ":41 ambiguous",
                        "uses json:BankAccount.name client/src/accounts.ts:3 ambiguous",
                        "uses json:BankAccount.id client/src/accounts.ts:3 ambiguous",
                        "uses json:BankAccount.balance client/src/accounts.ts:4 inferred");
    }

    /**
     * jhipster-sample-app's PasswordService (verbatim, at the same commit) posts {@code {
     * currentPassword, newPassword }}, shorthand keys, behind its {@code ${serverApiUrl}} base URL:
     * both are places that must change. Shorthand keys of a literal that is not sent (an analytics
     * call's, a returned value) are not uses at all.
     */
    @Test
    void aShorthandBodyIsTiedToTheRequestThatSendsIt(@TempDir Path dir) throws IOException {
        Map<String, String> files = clinic();
        String jh = "src/main/java/io/github/jhipster/sample/";
        files.put(jh + "service/dto/PasswordChangeDTO.java", """
                package io.github.jhipster.sample.service.dto;

                public class PasswordChangeDTO {
                    private String currentPassword;
                    private String newPassword;

                    public String getCurrentPassword() { return currentPassword; }
                    public void setCurrentPassword(String currentPassword) { this.currentPassword = currentPassword; }
                    public String getNewPassword() { return newPassword; }
                    public void setNewPassword(String newPassword) { this.newPassword = newPassword; }
                }
                """);
        files.put(jh + "web/rest/AccountResource.java", """
                package io.github.jhipster.sample.web.rest;

                import io.github.jhipster.sample.service.dto.PasswordChangeDTO;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestBody;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping("/api")
                public class AccountResource {
                    @PostMapping(path = "/account/change-password")
                    public void changePassword(@RequestBody PasswordChangeDTO passwordChangeDto) {}
                }
                """);
        String service = "src/main/webapp/app/account/password/password.service.ts";
        files.put(
                service, Files.readString(Path.of("src/test/resources/json-anchors/jhipster/password.service.ts.txt")));
        files.put("src/main/webapp/app/account/password/audit.ts", """
                export function change(currentPassword: string, newPassword: string) {
                  analytics.track({ currentPassword, newPassword });
                  http.post(`${serverApiUrl}api/account/change-password`, { currentPassword: currentPassword, newPassword });
                  return { currentPassword, newPassword };
                }
                """);
        RenameFixture ws = new RenameFixture(dir, files);
        AnchorEditsTest.refresh(ws);

        assertThat(jsonEdges(ws).stream()
                        .filter(e -> e.startsWith("uses ") && e.contains("app/account/password/"))
                        .toList())
                .containsExactlyInAnyOrder(
                        "uses json:PasswordChangeDTO.currentPassword " + service + ":13 inferred",
                        "uses json:PasswordChangeDTO.newPassword " + service + ":13 inferred",
                        "uses json:PasswordChangeDTO.currentPassword src/main/webapp/app/account/password/audit.ts:3 inferred",
                        "uses json:PasswordChangeDTO.newPassword src/main/webapp/app/account/password/audit.ts:3 inferred");
    }

    /**
     * jk's own tree has routes only in documents (the bench's OpenAPI files), no handler: the pass
     * stops at finding no route a JVM handler defines, reading no file and no class, and the graph
     * is the one it was given.
     */
    @Test
    void aGraphWhoseRoutesNoHandlerDefinesReadsNothing(@TempDir Path dir) {
        String doc = "bench/tasks/openapi.yaml";
        CodeGraph g = Linker.link(List.of(new Fragment(
                List.of(
                        new GraphNode(GraphIds.file(doc), "openapi.yaml", "code", doc, ""),
                        Anchors.node(Anchors.ROUTE, "GET /api/owners/{id}/card")),
                List.of(Anchors.edge(
                        GraphIds.file(doc),
                        Anchors.ROUTE,
                        "GET /api/owners/{id}/card",
                        Relations.DEFINES,
                        Confidence.DECLARED,
                        doc,
                        9)))));
        List<String> read = new ArrayList<>();
        CodeGraph out = JsonAnchors.add(
                g,
                List.of(doc, "client/src/card.ts"),
                rel -> {
                    read.add(rel);
                    return "";
                },
                new JsonAnchors.Classfiles(List.of(dir.resolve("absent")), Set.of()));
        assertThat(out).isSameAs(g);
        assertThat(read).isEmpty();
        assertThat(dir.resolve("absent")).doesNotExist();
    }
}
