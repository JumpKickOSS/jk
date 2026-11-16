// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A class renamed (JK-PSJ0A), a package renamed (JK-W6DYS) and a type moved (JK-DX4VH) on the
 * petclinic-shaped fixture: every name javac resolved to the type is edited, its file moves; a
 * services file is a declared candidate, a JPQL entity name a must-review, a test named after the
 * type a judgment call; the proof after the compile holds.
 */
class RenameTypeTest {

    private static final String OWNER_DIR = RenameFieldTest.OWNER_DIR;

    @Test
    void aClassItsFileAndEveryUseJavacResolvedAreRenamed(@TempDir Path dir) throws IOException {
        RenameFixture ws = new RenameFixture(dir, RenameFieldTest.petclinic());
        String answer = ws.rename("Owner", "Customer");

        assertThat(answer)
                .startsWith("Renamed org.acme.petclinic.owner.Owner (class) to Customer:")
                .contains(OWNER_DIR + "Owner.java -> " + OWNER_DIR + "Customer.java");
        assertThat(ws.exists(OWNER_DIR + "Owner.java")).isFalse();
        assertThat(ws.read(OWNER_DIR + "Customer.java"))
                .contains("public class Customer {", "public Customer() {}", "public Customer(String telephone)");
        assertThat(ws.read(OWNER_DIR + "OwnerController.java"))
                .contains("String show(Customer owner)", "Customer.class.getDeclaredField")
                .as("a JPQL string is a candidate")
                .contains("select o from Owner o");
        assertThat(ws.read("src/main/java/org/acme/petclinic/vet/Clinic.java"))
                .contains("Customer newcomer()", "return new Customer(\"0\");");
        assertThat(ws.read("src/main/java/org/acme/petclinic/vet/Vets.java"))
                .contains("org.acme.petclinic.owner.Customer first;");
        assertThat(ws.read("src/test/java/org/acme/petclinic/owner/OwnerTests.java"))
                .contains("Customer o = new Customer(\"1\");");
        assertThat(answer)
                .containsPattern("c\\d+ inferred, JPQL entity name: " + OWNER_DIR + "OwnerController.java:5")
                .containsPattern(
                        "c\\d+ inferred, Kotlin source, matched by name: src/main/kotlin/org/acme/petclinic/owner/Report.kt:3")
                .containsPattern(
                        "c\\d+ inferred, test named after it: src/test/java/org/acme/petclinic/owner/OwnerTests.java:3"
                                + " +\\(rename OwnerTests CustomerTests\\)");
        assertThat(ws.check()).startsWith("Proven: it compiles, and org.acme.petclinic.owner.Customer has the");
    }

    @Test
    void aServicesFileNamingTheClassIsADeclaredCandidateAndAlsoDeclaredAppliesIt(@TempDir Path dir) throws IOException {
        RenameFixture ws = new RenameFixture(dir, RenameFieldTest.petclinic());
        String answer = ws.rename("OwnerPlugin", "CustomerPlugin");
        assertThat(answer)
                .containsPattern(
                        "c1 declared, class list: src/main/resources/META-INF/services/org.acme.petclinic.Plugin:1"
                                + " \\| org.acme.petclinic.owner.OwnerPlugin");
        assertThat(ws.read("src/main/resources/META-INF/services/org.acme.petclinic.Plugin"))
                .contains("OwnerPlugin");

        ws.rename("OwnerPlugin", "CustomerPlugin", "also=declared");
        assertThat(ws.read("src/main/resources/META-INF/services/org.acme.petclinic.Plugin"))
                .isEqualTo("org.acme.petclinic.owner.CustomerPlugin\n");
    }

    @Test
    void aNameAUserAlreadyImportsForAnotherTypeIsRefused(@TempDir Path dir) throws IOException {
        Map<String, String> files = RenameFieldTest.petclinic();
        files.put("src/main/java/org/acme/crm/Customer.java", "package org.acme.crm;\n\npublic class Customer {}\n");
        files.put("src/main/java/org/acme/petclinic/vet/Both.java", """
                package org.acme.petclinic.vet;

                import org.acme.crm.Customer;
                import org.acme.petclinic.owner.Owner;

                class Both {
                    Owner o;
                    Customer c;
                }
                """);
        RenameFixture ws = new RenameFixture(dir, files);
        assertThat(ws.rename("org.acme.petclinic.owner.Owner", "Customer"))
                .startsWith("Not renamed: these files use Owner and already name another Customer")
                .contains("src/main/java/org/acme/petclinic/vet/Both.java");
        assertThat(ws.exists(OWNER_DIR + "Owner.java")).isTrue();
    }
}
