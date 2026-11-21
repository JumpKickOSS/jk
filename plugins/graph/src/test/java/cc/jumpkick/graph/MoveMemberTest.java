// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A move takes a static method (every overload) or a static field to a class in another package,
 * edits every use javac bound (a qualifier, a method reference, a static import, a doc link, an
 * unqualified call in the source type), fixes the imports on both sides, then compiles and proves the
 * moved symbols have the references the old ones had. What would break or change without a compile
 * error is listed and nothing is written.
 */
class MoveMemberTest {

    static final String SHOP = "src/main/java/org/acme/shop/";
    static final String TEXT = "src/main/java/org/acme/text/";
    static final String REPORT = "src/main/java/org/acme/report/";

    static final String ORDER = """
            package org.acme.shop;

            import java.util.List;

            /** An order. */
            public class Order {

                /** The separator between parts. */
                public static final String SEP = ", ";

                private static int count;

                private String status;

                public Order(String status) {
                    this.status = status;
                    count++;
                }

                public String status() {
                    return status;
                }

                /** Joins parts with {@link #SEP}. */
                public static String join(List<String> parts) {
                    return String.join(SEP, parts);
                }

                public static String join(String a, String b) {
                    return join(List.of(a, b));
                }

                public String summary() {
                    return join(status, "open");
                }

                public static int made() {
                    return count;
                }

                static String tag(String s) {
                    return "#" + s;
                }

                private static String quiet(String s) {
                    return s.toLowerCase();
                }

                public static String shout(String s) {
                    return quiet(s).toUpperCase();
                }

                public static String trim(String s) {
                    return s.trim();
                }
            }
            """;

    static final String FORMATS = """
            package org.acme.text;

            /** Text helpers. */
            public final class Formats {

                private Formats() {}

                public static String trim(String s) {
                    return s.strip();
                }
            }
            """;

    static Map<String, String> shop() {
        return RenameFixture.files(
                SHOP + "Order.java",
                ORDER,
                SHOP + "Checkout.java",
                """
                package org.acme.shop;

                import java.util.List;
                import java.util.function.Function;

                class Checkout {
                    String total(Order order) {
                        Function<List<String>, String> f = Order::join;
                        return Order.join(order.status(), Order.tag("x")) + f.apply(List.of("a"));
                    }

                    String line(Order order) {
                        return order.summary();
                    }
                }
                """,
                REPORT + "Report.java",
                """
                package org.acme.report;

                import static org.acme.shop.Order.join;

                import org.acme.shop.Order;

                /** Uses {@link Order#join(String, String)}. */
                public class Report {
                    public String line(String a) {
                        return join(a, "b");
                    }
                }
                """,
                TEXT + "Formats.java",
                FORMATS);
    }

    @Test
    void aStaticMethodMovesWithEveryOverloadAndEveryUseThenProves(@TempDir Path dir) throws IOException {
        RenameFixture f = new RenameFixture(dir, shop());

        String answer = f.move("Order.join", "org.acme.text.Formats");

        assertThat(answer).startsWith("Moved Order.join() (2 overloads) to org.acme.text.Formats: ");
        assertThat(f.read(TEXT + "Formats.java")).isEqualTo("""
                package org.acme.text;

                import java.util.List;
                import org.acme.shop.Order;

                /** Text helpers. */
                public final class Formats {

                    private Formats() {}

                    public static String trim(String s) {
                        return s.strip();
                    }

                    /** Joins parts with {@link Order#SEP}. */
                    public static String join(List<String> parts) {
                        return String.join(Order.SEP, parts);
                    }

                    public static String join(String a, String b) {
                        return join(List.of(a, b));
                    }
                }
                """);
        String order = f.read(SHOP + "Order.java");
        assertThat(order)
                .doesNotContain("import java.util.List;")
                .contains("import org.acme.text.Formats;")
                .contains("return Formats.join(status, \"open\");")
                .doesNotContain("Joins parts")
                .contains("""
                            public String status() {
                                return status;
                            }

                            public String summary() {
                        """);
        assertThat(f.read(SHOP + "Checkout.java"))
                .contains("import java.util.function.Function;\nimport org.acme.text.Formats;\n")
                .contains("Function<List<String>, String> f = Formats::join;")
                .contains("return Formats.join(order.status(), Order.tag(\"x\"))");
        assertThat(f.read(REPORT + "Report.java")).isEqualTo("""
                package org.acme.report;

                import static org.acme.text.Formats.join;

                import org.acme.text.Formats;

                /** Uses {@link Formats#join(String, String)}. */
                public class Report {
                    public String line(String a) {
                        return join(a, "b");
                    }
                }
                """);
        assertThat(f.checkMove())
                .startsWith("Proven: it compiles, and the 2 moved symbols have the ")
                .endsWith("references they had, where the move put them; nothing else binds to the new symbol.");
    }

    @Test
    void aStaticFieldMovesAndTheSourceTypeQualifiesItsUses(@TempDir Path dir) throws IOException {
        RenameFixture f = new RenameFixture(dir, shop());

        assertThat(f.move("Order.SEP", "Formats")).startsWith("Moved Order.SEP to org.acme.text.Formats: ");

        assertThat(f.read(TEXT + "Formats.java")).contains("""
                    /** The separator between parts. */
                    public static final String SEP = ", ";
                }
                """);
        assertThat(f.read(SHOP + "Order.java"))
                .contains("/** Joins parts with {@link Formats#SEP}. */")
                .contains("return String.join(Formats.SEP, parts);")
                .doesNotContain("public static final String SEP");
        assertThat(f.checkMove())
                .isEqualTo("Proven: it compiles, and Formats.SEP has the 3 references Order.SEP had, where the move"
                        + " put them; nothing else binds to the new symbol.");
    }

    @Test
    void anInstanceMethodIsNotMovedAndWhatTiesItToItsTypeIsListed(@TempDir Path dir) throws IOException {
        RenameFixture f = new RenameFixture(dir, shop());

        String answer = f.move("Order.summary", "Formats");

        assertThat(answer)
                .startsWith("Not moved, nothing is written: Order.summary() is an instance method; a move takes static"
                        + " members, or an instance member up to the class its type extends.")
                .contains("instance state: Order.status  " + SHOP + "Order.java:34")
                .contains("1 use reaches it through an instance of org.acme.shop.Order:")
                .contains(SHOP + "Checkout.java:13");
        assertThat(f.read(SHOP + "Order.java")).isEqualTo(ORDER);
    }

    @Test
    void visibilityTheTargetCannotKeepIsListedAndNothingWritten(@TempDir Path dir) throws IOException {
        RenameFixture f = new RenameFixture(dir, shop());

        assertThat(f.move("Order.shout", "Formats"))
                .startsWith("Not moved, nothing is written: moving Order.shout() to org.acme.text.Formats would break")
                .contains("uses Order.quiet, which is private and org.acme.text.Formats would not see  " + SHOP
                        + "Order.java:");
        assertThat(f.move("Order.made", "Formats")).contains("uses Order.count, which is private");
        assertThat(f.move("Order.tag", "Formats"))
                .contains("Order.tag() is package-private; " + SHOP + "Checkout.java uses it and would not see it in"
                        + " org.acme.text.Formats");
        assertThat(f.move("Order.trim", "Formats")).contains("org.acme.text.Formats already declares trim  " + TEXT);
        assertThat(f.read(SHOP + "Order.java")).isEqualTo(ORDER);
        assertThat(f.read(TEXT + "Formats.java")).isEqualTo(FORMATS);
    }

    @Test
    void aStringNamingTheMemberIsAHazard(@TempDir Path dir) throws IOException {
        Map<String, String> files = shop();
        files.put(SHOP + "Probe.java", """
                package org.acme.shop;

                class Probe {
                    Object find() throws Exception {
                        return Order.class.getMethod("join", String.class, String.class);
                    }
                }
                """);
        RenameFixture f = new RenameFixture(dir, files);

        assertThat(f.move("Order.join", "Formats"))
                .startsWith("Not moved, nothing is written")
                .contains("a string names it (reflection?)  " + SHOP + "Probe.java:5");
        assertThat(f.read(SHOP + "Order.java")).isEqualTo(ORDER);
    }

    @Test
    void aMoveThatDoesNotCompileIsUndoneWhole(@TempDir Path dir) throws IOException {
        Map<String, String> files = shop();
        // Constants with no `;` after them take no members: the moved method makes the enum invalid.
        files.put(TEXT + "Mode.java", "package org.acme.text;\n\npublic enum Mode {\n    PLAIN,\n    LOUD\n}\n");
        RenameFixture f = new RenameFixture(dir, files);

        assertThat(f.move("Order.trim", "Mode")).startsWith("Moved Order.trim() to org.acme.text.Mode: ");
        assertThat(f.checkMove())
                .startsWith("Rolled back, nothing is changed (2 files restored): after the move the code"
                        + " does not compile (");
        assertThat(f.read(SHOP + "Order.java")).isEqualTo(ORDER);
        assertThat(f.read(TEXT + "Mode.java")).doesNotContain("trim");
    }

    @Test
    void aStaticThatASubtypeHidesIsAHazard(@TempDir Path dir) throws IOException {
        Map<String, String> files = shop();
        files.put(SHOP + "Special.java", """
                package org.acme.shop;

                class Special extends Order {
                    Special() {
                        super("special");
                    }

                    public static String shout(String s) {
                        return s + "!";
                    }
                }
                """);
        RenameFixture f = new RenameFixture(dir, files);

        assertThat(f.move("Order.shout", "Checkout")).contains("hidden by Special.shout()  " + SHOP + "Special.java:8");
    }

    @Test
    void aMemberMovesToATypeNestedInTheSameFile(@TempDir Path dir) throws IOException {
        Map<String, String> files = shop();
        files.put(SHOP + "Basket.java", """
                package org.acme.shop;

                public class Basket {

                    static int size(String s) {
                        return s.length();
                    }

                    int total(String s) {
                        return size(s) * 2;
                    }

                    static final class Sizes {
                        private Sizes() {}
                    }
                }
                """);
        RenameFixture f = new RenameFixture(dir, files);

        assertThat(f.move("Basket.size", "Basket.Sizes"))
                .startsWith("Moved Basket.size() to org.acme.shop.Basket.Sizes: ");
        assertThat(f.read(SHOP + "Basket.java")).isEqualTo("""
                package org.acme.shop;

                public class Basket {

                    int total(String s) {
                        return Basket.Sizes.size(s) * 2;
                    }

                    static final class Sizes {
                        private Sizes() {}

                        static int size(String s) {
                            return s.length();
                        }
                    }
                }
                """);
        assertThat(f.checkMove())
                .startsWith("Proven: it compiles, and Sizes.size has the 2 references Basket.size had");
    }
}
