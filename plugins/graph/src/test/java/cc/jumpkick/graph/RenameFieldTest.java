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
 * A field renamed as a bean property, on a petclinic-shaped fixture with the traps the rename bench
 * hides (JK-N63YH): a template bean path that must change, a SQL column and a message key that must
 * not change unasked, a JPA entity whose column follows the field's name, a local variable of the
 * same name, a reflection string, a JPQL path and a Kotlin source javac does not index.
 */
class RenameFieldTest {

    static final String OWNER_DIR = "src/main/java/org/acme/petclinic/owner/";

    static Map<String, String> petclinic() {
        return RenameFixture.files(
                "src/main/java/jakarta/persistence/Entity.java",
                "package jakarta.persistence;\n\npublic @interface Entity {}\n",
                "src/main/java/jakarta/persistence/Column.java",
                "package jakarta.persistence;\n\npublic @interface Column {\n    String name() default \"\";\n}\n",
                "src/main/java/jakarta/persistence/Table.java",
                "package jakarta.persistence;\n\npublic @interface Table {\n    String name() default \"\";\n}\n",
                OWNER_DIR + "Owner.java",
                """
                package org.acme.petclinic.owner;

                import jakarta.persistence.Column;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Table;

                /** An owner; see {@link #telephone}. */
                @Entity
                @Table(name = "owners")
                public class Owner {

                    @Column(name = "address")
                    private String address;

                    @Column
                    private String telephone;

                    public Owner() {}

                    public Owner(String telephone) {
                        this.telephone = telephone;
                    }

                    public String getAddress() {
                        return address;
                    }

                    public String getTelephone() {
                        return telephone;
                    }

                    public void setTelephone(String telephone) {
                        this.telephone = telephone;
                    }
                }
                """,
                OWNER_DIR + "OwnerController.java",
                """
                package org.acme.petclinic.owner;

                /** Shows an {@link Owner}. */ class OwnerController {

                    static final String FIND = "select o from Owner o where o.telephone = ?1";

                    String show(Owner owner) {
                        String telephone = owner.getTelephone();
                        return telephone.trim();
                    }

                    void update(Owner owner, String number) throws Exception {
                        owner.setTelephone(number);
                        // the telephone must be ten digits
                        Owner.class.getDeclaredField("telephone");
                    }
                }
                """,
                OWNER_DIR + "OwnerPlugin.java",
                "package org.acme.petclinic.owner;\n\npublic class OwnerPlugin implements org.acme.petclinic.Plugin {}\n",
                "src/main/java/org/acme/petclinic/Plugin.java",
                "package org.acme.petclinic;\n\npublic interface Plugin {}\n",
                "src/main/java/org/acme/petclinic/vet/Clinic.java",
                """
                package org.acme.petclinic.vet;

                import org.acme.petclinic.owner.*;

                public class Clinic {
                    Owner newcomer() {
                        return new Owner("0");
                    }
                }
                """,
                "src/main/java/org/acme/petclinic/vet/Vets.java",
                """
                package org.acme.petclinic.vet;

                public class Vets {
                    org.acme.petclinic.owner.Owner first;
                }
                """,
                "src/main/kotlin/org/acme/petclinic/owner/Report.kt",
                "package org.acme.petclinic.owner\n\nfun line(o: Owner) = \"call \" + o.telephone\n",
                "src/main/resources/templates/owners/ownerDetails.html",
                """
                <table th:object="${owner}">
                  <tr><th th:text="#{telephone}">Telephone</th><td th:text="*{telephone}"></td></tr>
                  <tr><td th:text="${owner.telephone}"></td></tr>
                </table>
                """,
                "src/main/resources/templates/owners/createOrUpdateOwnerForm.html",
                "<input th:replace=\"~{fragments/inputField :: input (#{telephone}, 'telephone', 'text')}\" />\n",
                "src/main/resources/messages/messages.properties",
                "telephone=Telephone\naddress=Address\n",
                "src/main/resources/db/schema.sql",
                "CREATE TABLE owners (\n  address VARCHAR(255),\n  telephone VARCHAR(20)\n);\n",
                "src/main/resources/META-INF/services/org.acme.petclinic.Plugin",
                "org.acme.petclinic.owner.OwnerPlugin\n",
                "src/test/java/org/acme/petclinic/owner/OwnerTests.java",
                """
                package org.acme.petclinic.owner;

                class OwnerTests {
                    static final class Request {
                        Request param(String name, String value) {
                            return this;
                        }
                    }

                    Request post() {
                        return new Request().param("telephone", "1316761638");
                    }

                    boolean keeps() {
                        Owner o = new Owner("1");
                        o.setTelephone("2");
                        return o.getTelephone().equals("2");
                    }
                }
                """);
    }

    @Test
    void theFieldItsAccessorsAndEveryBoundUseAreRenamedAndTheRestReturnedGraded(@TempDir Path dir) throws IOException {
        RenameFixture ws = new RenameFixture(dir, petclinic());
        String answer = ws.rename("Owner.telephone", "phone");

        assertThat(answer)
                .startsWith("Renamed Owner.telephone (field, with getTelephone, setTelephone) to phone:")
                .contains("each one javac or the compiled classes bound");
        assertThat(ws.read(OWNER_DIR + "Owner.java"))
                .contains(
                        "private String phone;",
                        "this.phone = telephone;",
                        "public String getPhone()",
                        "return phone;",
                        "public void setPhone(String telephone)",
                        "{@link #phone}")
                .as("a parameter of the same name is not the field")
                .contains("public Owner(String telephone)");
        assertThat(ws.read(OWNER_DIR + "OwnerController.java"))
                .contains("String telephone = owner.getPhone();", "return telephone.trim();", "owner.setPhone(number);")
                .as("strings and comments are candidates, not edits")
                .contains("getDeclaredField(\"telephone\")", "o.telephone = ?1", "// the telephone must");
        assertThat(ws.read("src/test/java/org/acme/petclinic/owner/OwnerTests.java"))
                .contains("o.setPhone(\"2\");", "o.getPhone().equals");
        assertThat(ws.read("src/main/resources/templates/owners/ownerDetails.html"))
                .contains("*{telephone}");

        assertThat(answer)
                .contains("Not applied, and should change too")
                .containsPattern(
                        "c\\d+ inferred, template bean path: src/main/resources/templates/owners/ownerDetails.html:2")
                .containsPattern(
                        "c\\d+ inferred, template bean path: src/main/resources/templates/owners/ownerDetails.html:3")
                .containsPattern(
                        "c\\d+ inferred, JPQL path: src/main/java/org/acme/petclinic/owner/OwnerController.java:5")
                .containsPattern(
                        "c\\d+ inferred, reflection string: src/main/java/org/acme/petclinic/owner/OwnerController.java:15")
                .containsPattern(
                        "c\\d+ inferred, Kotlin source, matched by name: src/main/kotlin/org/acme/petclinic/owner/Report.kt:3")
                .contains("Judgment, left as they are unless chosen")
                .containsPattern("c\\d+ inferred, SQL column: src/main/resources/db/schema.sql:3")
                .containsPattern("c\\d+ inferred, message key: src/main/resources/messages/messages.properties:1")
                .containsPattern(
                        "c\\d+ inferred, comment: src/main/java/org/acme/petclinic/owner/OwnerController.java:14")
                .containsPattern(
                        "c\\d+ inferred, template field name: src/main/resources/templates/owners/createOrUpdateOwnerForm.html:1")
                .containsPattern(
                        "c\\d+ inferred, bean property name in a string: src/test/java/org/acme/petclinic/owner/OwnerTests.java:11")
                .contains("h1 hazard: Owner is a JPA entity and telephone names no column")
                .as("a local variable javac bound to itself is never a candidate")
                .doesNotContain("OwnerController.java:8 |");
    }

    @Test
    void theProofAfterTheCompileFindsEveryReferenceAtTheNewName(@TempDir Path dir) throws IOException {
        RenameFixture ws = new RenameFixture(dir, petclinic());
        ws.rename("Owner.telephone", "phone");
        assertThat(ws.check())
                .startsWith("Proven: it compiles, and the 3 renamed symbols have the")
                .contains("at the same lines; nothing else binds to the new name.");
    }

    @Test
    void chosenCandidatesAndThePinApplyInALaterCallAndOnlyWhereTheLineStillReadsTheSame(@TempDir Path dir)
            throws IOException {
        RenameFixture ws = new RenameFixture(dir, petclinic());
        String answer = ws.rename("Owner.telephone", "phone");
        String c1 = id(answer, "template bean path: src/main/resources/templates/owners/ownerDetails.html:2");
        String c2 = id(answer, "template bean path: src/main/resources/templates/owners/ownerDetails.html:3");

        String applied = ws.rename("Owner.telephone", "phone", "also=" + c1 + "," + c2 + ",h1");

        assertThat(applied).startsWith("Applied " + c1 + "," + c2 + ",h1: 3 changes in 2 files.");
        assertThat(ws.read("src/main/resources/templates/owners/ownerDetails.html"))
                .contains("*{phone}", "${owner.phone}")
                .as("a message key in the same line is a judgment call, left")
                .contains("#{telephone}");
        assertThat(ws.read(OWNER_DIR + "Owner.java"))
                .contains("    @Column(name = \"telephone\")\n    private String phone;")
                .doesNotContain("@Column\n");
        assertThat(ws.read("src/main/resources/db/schema.sql")).contains("telephone VARCHAR(20)");
        assertThat(ws.check()).startsWith("Proven").contains("it compiles");
    }

    @Test
    void anAlsoOnTheRenamesOwnCallAppliesTheChosenKindWithTheBoundEdits(@TempDir Path dir) throws IOException {
        RenameFixture ws = new RenameFixture(dir, petclinic());
        String answer = ws.rename("Owner.telephone", "phone", "also=hazards");
        assertThat(answer).contains("1 chosen change").doesNotContain("h1 hazard");
        assertThat(ws.read(OWNER_DIR + "Owner.java"))
                .contains("@Column(name = \"telephone\")\n    private String phone;");
    }

    @Test
    void aRenameALocalVariableWouldShadowIsRolledBackByTheProof(@TempDir Path dir) throws IOException {
        RenameFixture ws = new RenameFixture(dir, RenameFixture.files("src/main/java/a/Counter.java", """
                        package a;

                        class Counter {
                            int count;

                            int next() {
                                int total = 1;
                                return count + total;
                            }
                        }
                        """));
        String answer = ws.rename("Counter.count", "total");
        assertThat(answer).startsWith("Renamed Counter.count (field) to total:");
        assertThat(ws.read("src/main/java/a/Counter.java")).contains("return total + total;");

        assertThat(ws.check())
                .startsWith(
                        "Rolled back, nothing is changed (1 file restored): after the edit javac resolves the new name"
                                + " differently")
                .contains("Counter.total at src/main/java/a/Counter.java:8: 0 references, 1 expected");
        assertThat(ws.read("src/main/java/a/Counter.java")).contains("int count;", "return count + total;");
    }

    @Test
    void aRenameThatDoesNotCompileIsUndoneWhole(@TempDir Path dir) throws IOException {
        RenameFixture ws = new RenameFixture(dir, petclinic());
        ws.rename("Owner.telephone", "phone");
        // A use only a later edit would have fixed: the engine's compile fails, so the check undoes the rename.
        String controller = ws.read(OWNER_DIR + "OwnerController.java");
        Files.writeString(
                ws.root.resolve(OWNER_DIR + "OwnerController.java"),
                controller.replace("getPhone()", "getTelephone()"));
        String undone = ws.check();
        assertThat(undone).startsWith("Rolled back, nothing is changed").contains("the code does not compile");
        assertThat(ws.read(OWNER_DIR + "Owner.java")).contains("private String telephone;", "getTelephone()");
    }

    @Test
    void aFieldTheTypeAlreadyHasUnderTheNewNameIsRefused(@TempDir Path dir) throws IOException {
        RenameFixture ws = new RenameFixture(dir, petclinic());
        assertThat(ws.rename("Owner.telephone", "address"))
                .isEqualTo("Not renamed: Owner already has a field address.");
        assertThat(ws.read(OWNER_DIR + "Owner.java")).contains("private String telephone;");
    }

    @Test
    void accessorsFalseRenamesTheFieldAlone(@TempDir Path dir) throws IOException {
        RenameFixture ws = new RenameFixture(dir, petclinic());
        String answer = ws.rename("Owner.telephone", "phone", "accessors=false");
        assertThat(answer).startsWith("Renamed Owner.telephone (field) to phone:");
        assertThat(ws.read(OWNER_DIR + "Owner.java")).contains("private String phone;", "public String getTelephone()");
        assertThat(ws.check()).startsWith("Proven");
    }

    /** The id the answer gave the candidate whose line contains {@code text}. */
    static String id(String answer, String text) {
        for (String line : answer.lines().toList()) {
            if (line.contains(text))
                return line.strip().substring(0, line.strip().indexOf(' '));
        }
        throw new AssertionError("no candidate " + text + " in:\n" + answer);
    }
}
