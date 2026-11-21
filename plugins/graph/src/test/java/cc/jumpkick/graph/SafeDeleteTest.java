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
 * A delete removes a method, a field or a type only when nothing uses it, then compiles; with a use
 * left it writes nothing and lists what uses it. The fixture holds the uses a grep would miss or
 * misread: a reflection string, a services file, an override, a framework annotation.
 */
class SafeDeleteTest {

    static final String DIR = "src/main/java/org/acme/shop/";

    static final String ORDER = """
            package org.acme.shop;

            /** An order. */
            public class Order {

                private String status;

                /** Never read. */
                private int legacyCount;

                private String note;

                public Order(String status) {
                    this.status = status;
                }

                public String status() {
                    return status;
                }

                /** Formats the old way. */
                @Deprecated
                public String legacyFormat() {
                    return "[" + status + "]";
                }

                public String legacyFormat(int width) {
                    return legacyFormat().substring(0, width);
                }

                public String label() {
                    return status.toUpperCase();
                }

                @Scheduled
                public void sweep() {}

                @Override
                public String toString() {
                    return status;
                }

                static class Draft {
                    String text;
                }
            }
            """;

    static Map<String, String> shop() {
        return RenameFixture.files(
                DIR + "Scheduled.java",
                "package org.acme.shop;\n\npublic @interface Scheduled {}\n",
                DIR + "Order.java",
                ORDER,
                DIR + "Checkout.java",
                """
                package org.acme.shop;

                class Checkout {
                    String total(Order order) throws Exception {
                        Order.class.getDeclaredField("note");
                        return order.label();
                    }
                }
                """,
                DIR + "Unused.java",
                """
                package org.acme.shop;

                /** Nothing uses this. */
                class Unused {
                    int size() {
                        return new Unused().hashCode();
                    }
                }
                """,
                DIR + "Plugin.java",
                "package org.acme.shop;\n\npublic interface Plugin {}\n",
                DIR + "ShopPlugin.java",
                "package org.acme.shop;\n\npublic class ShopPlugin implements Plugin {}\n",
                "src/main/resources/META-INF/services/org.acme.shop.Plugin",
                "org.acme.shop.ShopPlugin\n");
    }

    @Test
    void anUnusedMethodGoesWithEveryOverloadAndItsDocThenCompiles(@TempDir Path dir) throws IOException {
        RenameFixture f = new RenameFixture(dir, shop());

        String answer = f.delete("Order.legacyFormat");

        assertThat(answer)
                .startsWith("Deleted Order.legacyFormat() (2 overloads), lines 20-29 of " + DIR + "Order.java");
        String order = f.read(DIR + "Order.java");
        assertThat(order)
                .doesNotContain("legacyFormat")
                .doesNotContain("Formats the old way")
                .doesNotContain("@Deprecated")
                .contains("""
                            public String status() {
                                return status;
                            }

                            public String label() {
                        """);
        assertThat(f.checkDelete()).isEqualTo("Proven: it compiles.");
    }

    @Test
    void aMethodWithACallerIsKeptAndTheCallerListedAsCallersDoes(@TempDir Path dir) throws IOException {
        RenameFixture f = new RenameFixture(dir, shop());

        String answer = f.delete("Order.label");

        assertThat(answer)
                .startsWith("Not deleted, nothing is written: Order.label() is still used.")
                .contains("Callers of Order.label(): 1 in main sources, 0 in tests.")
                .contains("Checkout.total()  " + DIR + "Checkout.java:6");
        assertThat(f.read(DIR + "Order.java")).isEqualTo(ORDER);
    }

    @Test
    void anOverrideAndAFrameworkAnnotationKeepAMethod(@TempDir Path dir) throws IOException {
        RenameFixture f = new RenameFixture(dir, shop());

        assertThat(f.delete("Order.toString"))
                .startsWith("Not deleted")
                .contains("overrides a method declared outside the workspace (@Override)");
        assertThat(f.delete("Order.sweep"))
                .startsWith("Not deleted")
                .contains(
                        "@Scheduled on it: a framework may call or create it by reflection  " + DIR + "Order.java:35");
        assertThat(f.read(DIR + "Order.java")).isEqualTo(ORDER);
    }

    @Test
    void eachFrameworkAnnotationIsNamedAtTheLineThatWritesIt(@TempDir Path dir) throws IOException {
        Map<String, String> files = shop();
        files.put(DIR + "Job.java", """
                package org.acme.shop;

                /** Runs on a timer. */
                @Scheduled
                @SuppressWarnings("unused")
                class Job {

                    @Scheduled
                    @Deprecated
                    int every;

                    @Deprecated
                    @org.acme.shop.Scheduled
                    void run() {}
                }
                """);
        RenameFixture f = new RenameFixture(dir, files);

        assertThat(f.delete("Job.every"))
                .startsWith("Not deleted")
                .contains("@Scheduled on it: a framework may call or create it by reflection  " + DIR + "Job.java:8");
        assertThat(f.delete("Job"))
                .startsWith("Not deleted")
                .contains("@Scheduled on it: a framework may call or create it by reflection  " + DIR + "Job.java:4")
                .contains("@Scheduled on Job.run(): a framework may call or create it by reflection  " + DIR
                        + "Job.java:13");
    }

    @Test
    void aMethodOnlyADeadTypeCallsGoesOnceTheTypeDoesAndOneCalledByNameStays(@TempDir Path dir) throws IOException {
        RenameFixture f = new RenameFixture(
                dir,
                RenameFixture.files(
                        DIR + "Owner.java", """
                package org.acme.shop;

                public class Owner {
                    String name = "Ada";

                    public String formatLegacyName() {
                        return name.toUpperCase();
                    }

                    public String describeForAudit() {
                        return "owner " + name;
                    }
                }
                """, DIR + "OwnerLegacyFormat.java", """
                package org.acme.shop;

                final class OwnerLegacyFormat {
                    static String format(Owner owner) {
                        return owner.formatLegacyName();
                    }
                }
                """, DIR + "OwnerAudit.java", """
                package org.acme.shop;

                final class OwnerAudit {
                    static final String DESCRIBE = "describeForAudit";

                    static Object line(Owner owner) throws ReflectiveOperationException {
                        return Owner.class.getMethod(DESCRIBE).invoke(owner);
                    }
                }
                """));

        assertThat(f.delete("Owner.formatLegacyName"))
                .startsWith("Not deleted")
                .contains("OwnerLegacyFormat.format()  " + DIR + "OwnerLegacyFormat.java:5");
        assertThat(f.delete("Owner.describeForAudit"))
                .startsWith("Not deleted")
                .contains("a string names it  " + DIR + "OwnerAudit.java:4");
        assertThat(f.delete("OwnerLegacyFormat")).startsWith("Deleted org.acme.shop.OwnerLegacyFormat, its file");
        assertThat(f.checkDelete()).isEqualTo("Proven: it compiles.");
        assertThat(f.delete("Owner.formatLegacyName")).startsWith("Deleted Owner.formatLegacyName()");
        assertThat(f.checkDelete()).isEqualTo("Proven: it compiles.");
        assertThat(f.read(DIR + "Owner.java"))
                .doesNotContain("formatLegacyName")
                .contains("describeForAudit");
    }

    @Test
    void anUnusedFieldGoesWithItsDocComment(@TempDir Path dir) throws IOException {
        RenameFixture f = new RenameFixture(dir, shop());

        assertThat(f.delete("Order.legacyCount")).startsWith("Deleted Order.legacyCount, lines 7-9 of ");
        assertThat(f.read(DIR + "Order.java"))
                .doesNotContain("legacyCount")
                .doesNotContain("Never read")
                .contains("    private String status;\n\n    private String note;\n");
        assertThat(f.checkDelete()).isEqualTo("Proven: it compiles.");
    }

    @Test
    void aFieldNamedInAReflectionStringIsKept(@TempDir Path dir) throws IOException {
        RenameFixture f = new RenameFixture(dir, shop());

        assertThat(f.delete("Order.note"))
                .startsWith("Not deleted, nothing is written: Order.note is still used.")
                .contains("a string names it  " + DIR + "Checkout.java:5  Order.class.getDeclaredField(\"note\");");
        assertThat(f.read(DIR + "Order.java")).isEqualTo(ORDER);
    }

    @Test
    void aReadFieldIsKeptWithItsUses(@TempDir Path dir) throws IOException {
        RenameFixture f = new RenameFixture(dir, shop());

        assertThat(f.delete("Order.status", "kind=field"))
                .startsWith("Not deleted")
                .contains("Uses of Order.status (field)")
                .contains("Order.label()");
    }

    @Test
    void aTypeAloneInItsFileTakesTheFileAndItsOwnUsesDoNotCount(@TempDir Path dir) throws IOException {
        RenameFixture f = new RenameFixture(dir, shop());

        assertThat(f.delete("Unused")).startsWith("Deleted org.acme.shop.Unused, its file " + DIR + "Unused.java");
        assertThat(f.exists(DIR + "Unused.java")).isFalse();
        assertThat(f.checkDelete()).isEqualTo("Proven: it compiles.");
    }

    @Test
    void aNestedTypeIsCutFromItsFile(@TempDir Path dir) throws IOException {
        RenameFixture f = new RenameFixture(dir, shop());

        assertThat(f.delete("Order.Draft")).startsWith("Deleted org.acme.shop.Order.Draft, lines ");
        assertThat(f.read(DIR + "Order.java")).doesNotContain("Draft").endsWith("        return status;\n    }\n}\n");
        assertThat(f.checkDelete()).isEqualTo("Proven: it compiles.");
    }

    @Test
    void aTypeAServicesFileNamesIsKept(@TempDir Path dir) throws IOException {
        RenameFixture f = new RenameFixture(dir, shop());

        assertThat(f.delete("ShopPlugin"))
                .startsWith("Not deleted")
                .contains("a string names it  src/main/resources/META-INF/services/org.acme.shop.Plugin:1");
        assertThat(f.exists(DIR + "ShopPlugin.java")).isTrue();
    }

    @Test
    void aDeleteThatDoesNotCompileIsUndoneWhole(@TempDir Path dir) throws IOException {
        RenameFixture f = new RenameFixture(dir, shop());
        assertThat(f.delete("Unused")).startsWith("Deleted");
        Files.writeString(
                f.root.resolve(DIR + "Broken.java"), "package org.acme.shop;\n\nclass Broken { int x = y; }\n");

        String check = f.checkDelete();

        assertThat(check).startsWith("Rolled back, nothing is changed (1 file restored): after the delete");
        assertThat(f.exists(DIR + "Unused.java")).isTrue();
    }

    @Test
    void aFieldBoundToAConfigurationKeyIsKeptAndTheKeyListed(@TempDir Path dir) throws IOException {
        Map<String, String> files = shop();
        files.put("src/main/java/org/springframework/boot/context/properties/ConfigurationProperties.java", """
                package org.springframework.boot.context.properties;

                public @interface ConfigurationProperties {
                    String prefix() default "";
                }
                """);
        files.put(DIR + "Limits.java", """
                package org.acme.shop;

                @org.springframework.boot.context.properties.ConfigurationProperties(prefix = "shop.limits")
                public class Limits {

                    private int retries = 3;
                }
                """);
        files.put("src/main/resources/application.properties", "shop.limits.retries=5\n");
        RenameFixture f = new RenameFixture(dir, files);

        String answer = f.delete("Limits.retries");

        assertThat(answer)
                .startsWith("Not deleted")
                .contains("shop.limits.retries")
                .contains("application.properties:1");
        assertThat(f.read(DIR + "Limits.java")).contains("retries");
    }
}
