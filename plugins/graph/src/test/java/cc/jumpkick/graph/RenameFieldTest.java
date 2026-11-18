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

                /** Shows an {@link Owner}. */ @org.acme.petclinic.Controller class OwnerController {

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
                "src/main/java/org/acme/petclinic/Controller.java",
                "package org.acme.petclinic;\n\npublic @interface Controller {}\n",
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

    /**
     * {@code also=all} sent with the rename, before the agent has seen a list, applies what must change
     * and the pin, and leaves the judgment calls listed by id: a task that keeps the column keeps it.
     */
    @Test
    void allOnTheRenamesOwnCallAppliesTheMustChangeUsesAndLeavesTheJudgmentCallsListed(@TempDir Path dir)
            throws IOException {
        RenameFixture ws = new RenameFixture(dir, petclinic());
        String answer = ws.rename("Owner.telephone", "phone", "also=all");

        assertThat(ws.read("src/main/resources/templates/owners/ownerDetails.html"))
                .contains("*{phone}", "${owner.phone}", "#{telephone}");
        assertThat(ws.read(OWNER_DIR + "Owner.java"))
                .contains("    @Column(name = \"telephone\")\n    private String phone;");
        assertThat(ws.read("src/main/resources/db/schema.sql")).contains("telephone VARCHAR(20)");
        assertThat(ws.read("src/main/resources/messages/messages.properties")).startsWith("telephone=Telephone\n");
        assertThat(answer)
                .contains("Judgment, left as they are unless chosen")
                .containsPattern("c\\d+ inferred, SQL column: src/main/resources/db/schema.sql:3")
                .containsPattern("c\\d+ inferred, message key: src/main/resources/messages/messages.properties:1")
                .doesNotContain("template bean path:");

        String column = id(answer, "SQL column: src/main/resources/db/schema.sql:3");
        assertThat(ws.rename("Owner.telephone", "phone", "also=" + column))
                .startsWith("Applied " + column + ": 1 change");
        assertThat(ws.read("src/main/resources/db/schema.sql")).contains("phone VARCHAR(20)");
    }

    /**
     * A {@code @ConfigurationProperties} field's key is set by environment variables too, under Spring's
     * relaxed binding: the compose file and the workflow of the petclinic-anchor-relaxed-env task. Each
     * must change with the key, or it silently stops setting it.
     */
    @Test
    void theEnvironmentVariablesThatSetAConfigPropertiesFieldsKeyAreCandidatesThatMustChange(@TempDir Path dir)
            throws IOException {
        RenameFixture ws = new RenameFixture(
                dir,
                RenameFixture.files(
                        "src/main/java/org/springframework/boot/context/properties/ConfigurationProperties.java",
                        """
                package org.springframework.boot.context.properties;

                public @interface ConfigurationProperties {
                    String value() default "";

                    String prefix() default "";
                }
                """,
                        "src/main/java/org/acme/petclinic/system/VisitLimits.java",
                        """
                package org.acme.petclinic.system;

                import org.springframework.boot.context.properties.ConfigurationProperties;

                /**
                 * How many visits the clinic books per day.
                 */
                @ConfigurationProperties(prefix = "petclinic.visits")
                public class VisitLimits {

                    private int maxPerDay = 10;

                    public int getMaxPerDay() {
                        return this.maxPerDay;
                    }

                    public void setMaxPerDay(int maxPerDay) {
                        this.maxPerDay = maxPerDay;
                    }
                }
                """,
                        "src/main/resources/application.properties",
                        "spring.application.name=petclinic\npetclinic.visits.max-per-day=20\n",
                        "docker-compose.yml",
                        """
                services:
                  app:
                    image: spring-petclinic:latest
                    profiles: ["app"]
                    ports:
                      - "8080:8080"
                    environment:
                      - SPRING_PROFILES_ACTIVE=h2
                      - PETCLINIC_VISITS_MAXPERDAY=40
                """,
                        ".github/workflows/load-test.yml",
                        """
                name: load test
                on: [workflow_dispatch]
                jobs:
                  load:
                    runs-on: ubuntu-latest
                    env:
                      PETCLINIC_VISITS_MAXPERDAY: 500
                """));

        String answer = ws.rename("org.acme.petclinic.system.VisitLimits.maxPerDay", "dailyLimit");

        assertThat(answer)
                .contains("Not applied, and should change too")
                .containsPattern(
                        "c\\d+ inferred, config key of petclinic.visits: src/main/resources/application.properties:2")
                .containsPattern(
                        "c\\d+ inferred, environment variable for petclinic.visits.max-per-day: docker-compose.yml:9")
                .containsPattern("c\\d+ inferred, environment variable for petclinic.visits.max-per-day:"
                        + " .github/workflows/load-test.yml:7");
        assertThat(ws.rename("org.acme.petclinic.system.VisitLimits.maxPerDay", "dailyLimit", "also=all"))
                .startsWith("Applied ");
        assertThat(ws.read("docker-compose.yml")).contains("      - PETCLINIC_VISITS_DAILYLIMIT=40\n");
        assertThat(ws.read(".github/workflows/load-test.yml")).contains("      PETCLINIC_VISITS_DAILYLIMIT: 500\n");
        assertThat(ws.read("src/main/resources/application.properties")).contains("petclinic.visits.daily-limit=20");
    }

    /**
     * Only the key Spring binds a {@code @ConfigurationProperties} field to must change, nested in YAML or
     * flat in properties: the word in a workflow, a compose file, a comment or another key is a judgment
     * call, which {@code also=all} on the rename's own call leaves.
     */
    @Test
    void onlyTheKeyAConfigPropertiesFieldBindsMustChangeNotTheWordInOtherYaml(@TempDir Path dir) throws IOException {
        RenameFixture ws = new RenameFixture(
                dir,
                RenameFixture.files(
                        "src/main/java/org/springframework/boot/context/properties/ConfigurationProperties.java",
                        """
                package org.springframework.boot.context.properties;

                public @interface ConfigurationProperties {
                    String value() default "";

                    String prefix() default "";
                }
                """,
                        "src/main/java/org/acme/mail/MailProperties.java",
                        """
                package org.acme.mail;

                import org.springframework.boot.context.properties.ConfigurationProperties;

                @ConfigurationProperties(prefix = "app.mail")
                public class MailProperties {

                    private String senderAddress;

                    public String getSenderAddress() {
                        return this.senderAddress;
                    }

                    public void setSenderAddress(String senderAddress) {
                        this.senderAddress = senderAddress;
                    }
                }
                """,
                        "src/main/resources/application.yml",
                        """
                app:
                  name: demo
                  mail:
                    sender-address: noreply@acme.org
                    # sender-address is the From line
                    reply-to: senderAddress
                """,
                        "src/main/resources/application-prod.properties",
                        "app.mail.sender-address=ops@acme.org\nother.sender-address=x\n",
                        ".github/workflows/ci.yml",
                        """
                name: ci
                on: [push]
                jobs:
                  build:
                    runs-on: ubuntu-latest
                    steps:
                      - name: check senderAddress
                        run: echo sender-address
                      - uses: acme/notify@v1
                        with:
                          sender: bot
                          sender-address: bot@acme.org
                """,
                        "docker-compose.yml",
                        """
                services:
                  mail:
                    image: mailhog/mailhog
                    environment:
                      senderAddress: noreply@acme.org
                    labels:
                      sender-address: smtp
                """));

        String answer = ws.rename("org.acme.mail.MailProperties.senderAddress", "fromAddress", "also=all");

        assertThat(ws.read("src/main/resources/application.yml"))
                .contains("    from-address: noreply@acme.org\n", "# sender-address is", "reply-to: senderAddress");
        assertThat(ws.read("src/main/resources/application-prod.properties"))
                .isEqualTo("app.mail.from-address=ops@acme.org\nother.sender-address=x\n");
        assertThat(ws.read(".github/workflows/ci.yml"))
                .contains("check senderAddress", "echo sender-address", "sender-address: bot@acme.org");
        assertThat(ws.read("docker-compose.yml")).contains("senderAddress: noreply", "sender-address: smtp");
        assertThat(answer)
                .contains("Judgment, left as they are unless chosen")
                .containsPattern("c\\d+-c\\d+ inferred, yml text: .github/workflows/ci.yml:7,8,12 \\(3\\)")
                .containsPattern("c\\d+ inferred, yml text: docker-compose.yml:5")
                .containsPattern("c\\d+ inferred, yml text: docker-compose.yml:7")
                .containsPattern("c\\d+ inferred, yml text: src/main/resources/application.yml:5")
                .containsPattern("c\\d+ inferred, properties text: src/main/resources/application-prod.properties:2")
                .doesNotContain("config key of app.mail: .github", "config key of app.mail: docker-compose");
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
