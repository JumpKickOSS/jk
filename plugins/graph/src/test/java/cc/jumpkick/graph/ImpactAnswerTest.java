// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code callers} answers what a change reaches in one call: on spring-petclinic's owner pages and H2
 * schema verbatim (at 500158f, Apache-2.0: the templates under view-anchors/petclinic, the schema
 * under impact/schema.sql.txt) and an Owner entity in petclinic's shape, a question about {@code
 * Owner.telephone} carries the templates and schema lines that name it, its setter's callers in the
 * tests and those tests; its getter, its column and its bean property each carry the rest of the
 * property. Before, each of those was a question of its own.
 */
class ImpactAnswerTest {

    static final String OWNER = ViewAnchorsTest.OWNER;
    static final String TESTS = "src/test/java/org/springframework/samples/petclinic/owner/OwnerControllerTests.java";

    static Map<String, String> petclinic() throws IOException {
        Map<String, String> files = ViewAnchorsTest.petclinic();
        files.put(
                "src/main/java/jakarta/persistence/Entity.java",
                "package jakarta.persistence;\n\npublic @interface Entity {}\n");
        files.put(
                "src/main/java/jakarta/persistence/Table.java",
                "package jakarta.persistence;\n\npublic @interface Table {\n    String name() default \"\";\n}\n");
        files.put(
                "src/main/java/jakarta/persistence/Column.java",
                "package jakarta.persistence;\n\npublic @interface Column {\n    String name() default \"\";\n}\n");
        files.put(
                "src/main/resources/db/h2/schema.sql",
                Files.readString(Path.of("src/test/resources/impact/schema.sql.txt")));
        files.put(OWNER, """
                package org.springframework.samples.petclinic.owner;

                import jakarta.persistence.Column;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Table;
                import java.util.ArrayList;
                import java.util.List;
                import org.springframework.samples.petclinic.model.Person;

                @Entity
                @Table(name = "owners")
                public class Owner extends Person {

                    @Column
                    private String address;

                    @Column
                    private String city;

                    @Column
                    private String telephone;

                    private final List<Pet> pets = new ArrayList<>();

                    public String getAddress() { return this.address; }

                    public String getCity() { return this.city; }

                    public String getTelephone() { return this.telephone; }

                    public void setTelephone(String telephone) { this.telephone = telephone; }

                    public List<Pet> getPets() { return this.pets; }
                }
                """);
        files.put(TESTS, """
                package org.springframework.samples.petclinic.owner;

                class OwnerControllerTests {

                    private Owner george() {
                        Owner george = new Owner();
                        george.setTelephone("6085551023");
                        return george;
                    }

                    void testShowOwner() {
                        george();
                    }
                }
                """);
        return files;
    }

    @Test
    void aFieldAnswersWithItsAnchorsPlacesItsAccessorsCallersAndTheTestsThatReachIt(@TempDir Path dir)
            throws IOException {
        RenameFixture ws = new RenameFixture(dir, petclinic());
        AnchorEditsTest.refresh(ws);

        String t = "src/main/resources/templates/owners/";
        assertThat(FileAnchorsTest.callers(ws, "Owner.telephone")).isEqualTo("""
                Uses of Owner.telephone (field): 2 in main sources, 0 in tests (1 write, 1 read).
                Complete: javac resolved each Java use at its line, and no Kotlin, Groovy or edited source spells the name; no search needed.
                Looked at: its Java uses, its accessors' callers, anchors it maps to (column, json, bean, config, cli; found bean, column) and the tests reaching those. Reflection and names built at run time are not looked at.
                anchors (places no compiler checks; must-change first):
                  bean:Owner.telephone: 3 uses:
                    %1$screateOrUpdateOwnerForm.html:14 (inferred)
                        L14| <input th:replace="~{fragments/inputField :: input (#{telephone}, 'telephone', 'text')}" />
                    %1$sownerDetails.html:32 (inferred)
                        L32| <td th:text="*{telephone}"></td>
                    %1$sownersList.html:26 (inferred)
                        L26| <td th:text="${owner.telephone}" />
                  column:owners.telephone: 1 definition:
                    src/main/resources/db/h2/schema.sql:42 (defines)
                        L42| telephone  VARCHAR(20)
                main:
                  writes:
                    Owner.setTelephone()  %2$s:31
                        L31| public void setTelephone(String telephone) { this.telephone = telephone; }
                  reads:
                    Owner.getTelephone()  %2$s:29
                        L29| public String getTelephone() { return this.telephone; }
                accessors (callers from the compiled classes):
                  Owner.getTelephone(): nothing in the compiled classes calls it.
                  Owner.setTelephone(): 0 in main sources, 1 in tests:
                    OwnerControllerTests.george()  %3$s:7
                        L7| george.setTelephone("6085551023");
                tests that reach it (1 class): OwnerControllerTests (george)""".formatted(t, OWNER, TESTS));
    }

    @Test
    void anAccessorAColumnAndABeanPropertyEachCarryTheRestOfTheProperty(@TempDir Path dir) throws IOException {
        RenameFixture ws = new RenameFixture(dir, petclinic());
        AnchorEditsTest.refresh(ws);

        assertThat(FileAnchorsTest.callers(ws, "Owner.getTelephone"))
                .contains("anchors (places no compiler checks; must-change first):\n  bean:Owner.telephone: 3 uses:\n")
                .contains(
                        "  column:owners.telephone: 1 definition:\n    src/main/resources/db/h2/schema.sql:42 (defines)\n")
                .endsWith("same property: Owner.setTelephone() 1 caller (OwnerControllerTests.java:7).");
        String java = "java side: Owner.telephone; Owner.getTelephone() 0 callers; Owner.setTelephone() 1 caller"
                + " (OwnerControllerTests.java:7); it also maps to ";
        assertThat(FileAnchorsTest.callers(ws, "column:owners.telephone"))
                .endsWith(java + "bean:Owner.telephone: callers 'Owner.telephone' lists them all.\n"
                        + "tests that reach it (1 class): OwnerControllerTests (george)");
        assertThat(FileAnchorsTest.callers(ws, "bean:Owner.telephone"))
                .endsWith(java + "column:owners.telephone: callers 'Owner.telephone' lists them all.\n"
                        + "tests that reach it (1 class): OwnerControllerTests (george)");
    }

    @Test
    void aTypeAnswersWithEveryAnchorItMapsToBeforeItsJavaUses(@TempDir Path dir) throws IOException {
        RenameFixture ws = new RenameFixture(dir, petclinic());
        AnchorEditsTest.refresh(ws);

        String answer = FileAnchorsTest.callers(ws, "Owner");
        String t = "src/main/resources/templates/owners/";
        assertThat(answer)
                .contains("""
                        anchors it maps to (8; places no compiler checks, callers '<anchor>' lists each):
                          bean:Owner.address (field address): 3 uses  %1$screateOrUpdateOwnerForm.html:12, %1$sownerDetails.html:24, %1$sownersList.html:24
                        """.formatted(t))
                .contains("  table:owners: 1 definition, ")
                .contains(
                        "  column:owners.telephone (field telephone): 1 definition  src/main/resources/db/h2/schema.sql:42\n");
        assertThat(answer.indexOf("anchors it maps to")).isLessThan(answer.indexOf("\nmain:"));
    }
}
