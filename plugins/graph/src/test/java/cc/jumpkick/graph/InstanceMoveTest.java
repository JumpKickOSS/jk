// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An instance method moves to a type it reaches through a field of its own type or one of its
 * parameters: when exactly one route reaches the target from every call it moves at once, every call
 * reaches the target through it, the moved code reads the route as {@code this} and the source
 * instance through a parameter when it needs it, then it compiles and is proven. With more such
 * routes, or none, it writes nothing and lists them; an override is refused and what would change is
 * listed.
 */
class InstanceMoveTest {

    static final String SHOP = "src/main/java/org/acme/shop/";
    static final String CRM = "src/main/java/org/acme/crm/";

    static final String CUSTOMER = """
            package org.acme.shop;

            public class Customer {

                private final String name;

                int visits;

                public Customer(String name) {
                    this.name = name;
                }

                public String name() {
                    return name;
                }
            }
            """;

    static final String ORDER = """
            package org.acme.shop;

            public class Order {

                final Customer customer;

                String status = "open";

                public Order(Customer customer) {
                    this.customer = customer;
                }

                /** A greeting for the customer of {@link #status this order}. */
                public String greeting() {
                    return "Dear " + customer.name();
                }

                public String line() {
                    return greeting() + " (" + status + ")";
                }

                public String label() {
                    return customer.name() + ": " + this.status;
                }

                /**
                 * Notes a visit.
                 *
                 * @param c who came
                 * @param times how many times
                 */
                public int visit(Customer c, int times) {
                    c.visits += times;
                    return c.visits;
                }

                public synchronized String held() {
                    return customer.name();
                }

                @Override
                public String toString() {
                    return status;
                }
            }
            """;

    static final String DESK = """
            package org.acme.shop;

            class Desk {
                String hello(Order order) {
                    return order.greeting();
                }

                String tag(Order order) {
                    return order.label() + order.held();
                }

                int come(Order order, Customer c) {
                    return order.visit(c, 2) + order.visit(null, 1);
                }
            }
            """;

    static Map<String, String> shop() {
        return RenameFixture.files(
                SHOP + "Customer.java", CUSTOMER, SHOP + "Order.java", ORDER, SHOP + "Desk.java", DESK);
    }

    @Test
    void oneRouteMovesAtOnceAndEveryCallGoesThroughTheField(@TempDir Path dir) throws IOException {
        RenameFixture f = new RenameFixture(dir, shop());

        String answer = f.move("Order.greeting", "Customer");

        assertThat(answer)
                .startsWith("Moved Order.greeting() to org.acme.shop.Customer: 2 uses and 0 imports in 3 files")
                .contains("  through f1, the field Order.customer; a call reads order.customer.greeting(), and fails"
                        + " where that field is null");
        assertThat(f.read(SHOP + "Customer.java")).contains("""
                    public String name() {
                        return name;
                    }

                    /** A greeting for the customer of {@link Order#status this order}. */
                    public String greeting() {
                        return "Dear " + this.name();
                    }
                }
                """);
        assertThat(f.read(SHOP + "Order.java"))
                .doesNotContain("Dear")
                .contains("return this.customer.greeting() + \" (\" + status + \")\";");
        assertThat(f.read(SHOP + "Desk.java")).contains("return order.customer.greeting();");
        assertThat(f.checkMove())
                .isEqualTo("Proven: it compiles, and Customer.greeting has the 3 references Order.greeting had, where"
                        + " the move put them; nothing else binds to the new symbol.");
    }

    @Test
    void aCallThatCannotSeeThePrivateFieldReadsItThroughItsGetter(@TempDir Path dir) throws IOException {
        Map<String, String> files = shop();
        files.put(
                SHOP + "Order.java",
                ORDER.replace("    final Customer customer;", "    private final Customer customer;")
                        .replace("    public String line() {", """
                            public Customer getCustomer() {
                                return customer;
                            }

                            public String line() {"""));
        RenameFixture f = new RenameFixture(dir, files);

        assertThat(f.move("Order.greeting", "Customer")).contains("a call reads order.getCustomer().greeting()");

        assertThat(f.read(SHOP + "Order.java")).contains("return this.customer.greeting() + \" (\" + status + \")\";");
        assertThat(f.read(SHOP + "Desk.java")).contains("return order.getCustomer().greeting();");
        assertThat(f.checkMove()).startsWith("Proven: it compiles, and Customer.greeting has the 3 references");
    }

    @Test
    void whatTheCodeReadsOfTheSourceIsPassedIn(@TempDir Path dir) throws IOException {
        RenameFixture f = new RenameFixture(dir, shop());

        assertThat(f.move("Order.label", "Customer"))
                .startsWith("Moved Order.label() to org.acme.shop.Customer: ")
                .contains("a call reads order.customer.label(order)");

        assertThat(f.read(SHOP + "Customer.java")).contains("""
                    public String label(Order order) {
                        return this.name() + ": " + order.status;
                    }
                """);
        assertThat(f.read(SHOP + "Desk.java")).contains("return order.customer.label(order) + order.held();");
        assertThat(f.checkMove()).startsWith("Proven: it compiles, and Customer.label has the 2 references");
    }

    @Test
    void twoRoutesEveryCallCanTakeAreListedThenTheOneNamedMovesIt(@TempDir Path dir) throws IOException {
        Map<String, String> files = shop();
        files.put(SHOP + "Desk.java", DESK.replace(" + order.visit(null, 1)", ""));
        RenameFixture f = new RenameFixture(dir, files);

        String listed = f.move("Order.visit", "Customer");

        assertThat(listed)
                .startsWith("Not moved, nothing is written: Order.visit() is an instance method; 2 routes reach"
                        + " org.acme.shop.Customer. Move it again with via=<route>:")
                .contains("  f1  through the field Order.customer\n")
                .contains(SHOP + "Desk.java:13  order.customer.visit(c, 2)")
                .contains("  p1  through the parameter Customer c\n")
                .contains(SHOP + "Desk.java:13  c.visit(2)");
        assertThat(f.read(SHOP + "Order.java")).isEqualTo(ORDER);
        assertThat(f.move("Order.visit", "Customer", "via=p9"))
                .startsWith("Not moved, nothing is written: p9 is not a route of Order.visit()");

        assertThat(f.move("Order.visit", "Customer", "via=f1")).startsWith("Moved Order.visit() to ");
        assertThat(f.read(SHOP + "Desk.java")).contains("return order.customer.visit(c, 2);");
        assertThat(f.checkMove()).startsWith("Proven: it compiles");
    }

    @Test
    void theOneRouteEveryCallCanTakeMovesAtOnceAndSaysWhyTheOtherWasNot(@TempDir Path dir) throws IOException {
        RenameFixture f = new RenameFixture(dir, shop());

        assertThat(f.move("Order.visit", "Customer", "via=p1"))
                .startsWith("Not moved, nothing is written: p1 does not reach org.acme.shop.Customer from every call")
                .contains(SHOP + "Desk.java:13  cannot: passes null for c");
        assertThat(f.read(SHOP + "Order.java")).isEqualTo(ORDER);

        assertThat(f.move("Order.visit", "Customer"))
                .startsWith("Moved Order.visit() to ")
                .contains("  through f1, the field Order.customer; a call reads order.customer.visit(c, 2)")
                .contains("  not through p1, the parameter Customer c: 1 of 2 calls cannot take it; " + SHOP
                        + "Desk.java:13  passes null for c");
        assertThat(f.read(SHOP + "Desk.java"))
                .contains("return order.customer.visit(c, 2) + order.customer.visit(null, 1);");
        assertThat(f.checkMove()).startsWith("Proven: it compiles");
    }

    @Test
    void whenNoRouteReachesTheTargetFromEveryCallTheyAreListed(@TempDir Path dir) throws IOException {
        Map<String, String> files = shop();
        String order = ORDER.replace("    final Customer customer;", "    private final Customer customer;");
        files.put(SHOP + "Order.java", order);
        RenameFixture f = new RenameFixture(dir, files);

        assertThat(f.move("Order.visit", "Customer"))
                .startsWith("Not moved, nothing is written: Order.visit() is an instance method, and no route reaches"
                        + " org.acme.shop.Customer from every call of it:")
                .contains("  f1  through the field Order.customer (2 of 2 calls cannot)")
                .contains(SHOP + "Desk.java:13  cannot: Order.customer is not visible here")
                .contains("  p1  through the parameter Customer c (1 of 2 calls cannot)");
        assertThat(f.read(SHOP + "Order.java")).isEqualTo(order);
    }

    @Test
    void throughAParameterTheArgumentBecomesTheReceiver(@TempDir Path dir) throws IOException {
        Map<String, String> files = shop();
        files.put(SHOP + "Desk.java", DESK.replace(" + order.visit(null, 1)", ""));
        RenameFixture f = new RenameFixture(dir, files);

        assertThat(f.move("Order.visit", "Customer", "via=p1")).startsWith("Moved Order.visit() to ");

        assertThat(f.read(SHOP + "Customer.java")).contains("""
                    /**
                     * Notes a visit.
                     *
                     * @param times how many times
                     */
                    public int visit(int times) {
                        this.visits += times;
                        return this.visits;
                    }
                """);
        assertThat(f.read(SHOP + "Desk.java")).contains("return c.visit(2);");
        assertThat(f.checkMove()).startsWith("Proven: it compiles, and Customer.visit has the 2 references");
    }

    @Test
    void aGenericConstructorOrTypeArgumentsAreReadAsOneReceiverAndOneArgument(@TempDir Path dir) throws IOException {
        Map<String, String> files =
                RenameFixture.files(SHOP + "Customer.java", CUSTOMER, SHOP + "Box.java", """
                package org.acme.shop;

                public class Box<K, V> {

                    final Customer customer;

                    K key;

                    public Box(Customer customer) {
                        this.customer = customer;
                    }

                    public static <K, V> Box<K, V> of(Customer c) {
                        return new Box<>(c);
                    }

                    public String tag(Customer c, Object o) {
                        return c.name() + key + o;
                    }
                }
                """, SHOP + "Desk.java", """
                package org.acme.shop;

                class Desk {
                    String made(Customer c) {
                        return new Box<String, Integer>(c).tag(c, new Box<String, Integer>(c));
                    }

                    String built(Customer c, int n) {
                        return Box.<String, Integer>of(c).tag(c, n > 1 ? "many" : "one");
                    }
                }
                """);
        RenameFixture f = new RenameFixture(dir, files);

        assertThat(f.move("Box.tag", "Customer"))
                .startsWith("Moved Box.tag() to ")
                .contains("  through p1, the parameter Customer c; a call reads c.tag(new Box<String, Integer>(c),")
                .contains("  not through f1, the field Box.customer: 2 of 2 calls cannot take it; " + SHOP
                        + "Desk.java:5  its receiver would run twice");

        assertThat(f.read(SHOP + "Desk.java"))
                .contains("return c.tag(new Box<String, Integer>(c), new Box<String, Integer>(c));")
                .contains("return c.tag(Box.<String, Integer>of(c), n > 1 ? \"many\" : \"one\");");
        assertThat(f.checkMove()).startsWith("Proven: it compiles, and Customer.tag has the 3 references");
    }

    @Test
    void aCallFromAClassInsideTheSourceRunsOnTheSourceInstance(@TempDir Path dir) throws IOException {
        Map<String, String> files = shop();
        files.put(SHOP + "Order.java", ORDER.replace("    public String line() {", """
                    class Copy {
                        final Customer customer = new Customer("copy");

                        String hello() {
                            return greeting();
                        }
                    }

                    Runnable greeter() {
                        return new Runnable() {
                            public void run() {
                                System.out.println(greeting());
                            }
                        };
                    }

                    String later() {
                        java.util.function.Supplier<String> s = () -> greeting();
                        return s.get();
                    }

                    public String line() {"""));
        RenameFixture f = new RenameFixture(dir, files);

        assertThat(f.move("Order.greeting", "Customer")).startsWith("Moved Order.greeting() to ");

        assertThat(f.read(SHOP + "Order.java"))
                .contains("return Order.this.customer.greeting();")
                .contains("System.out.println(Order.this.customer.greeting());")
                .contains("() -> this.customer.greeting();");
        assertThat(f.checkMove()).startsWith("Proven: it compiles, and Customer.greeting has the 6 references");
    }

    @Test
    void aCallFromAClassInsideTheSourceThatInheritsTheMethodIsRefused(@TempDir Path dir) throws IOException {
        Map<String, String> files = shop();
        String order = ORDER.replace("    public String line() {", """
                    class Copy extends Order {
                        Copy() {
                            super(new Customer("copy"));
                        }

                        String hello() {
                            return greeting();
                        }
                    }

                    Runnable greeter() {
                        return new Runnable() {
                            final Order other = new Order(new Customer("other")) {
                                String hello() {
                                    return greeting();
                                }
                            };

                            public void run() {
                                System.out.println(greeting());
                            }
                        };
                    }

                    public String line() {""");
        files.put(SHOP + "Order.java", order);
        RenameFixture f = new RenameFixture(dir, files);

        assertThat(f.move("Order.greeting", "Customer"))
                .startsWith("Not moved, nothing is written: moving Order.greeting() to org.acme.shop.Customer"
                        + " through f1 would break or change:")
                .contains("a call from a nested or anonymous class that may inherit it, where this is another"
                        + " instance  " + SHOP + "Order.java:24")
                .contains("a call from a nested or anonymous class that may inherit it, where this is another"
                        + " instance  " + SHOP + "Order.java:32")
                .contains(SHOP + "Order.java:37  Order.this.customer.greeting()");
        assertThat(f.read(SHOP + "Order.java")).isEqualTo(order);
    }

    @Test
    void aCallAfterATernaryOrCaseColonIsUnqualified(@TempDir Path dir) throws IOException {
        Map<String, String> files = shop();
        files.put(SHOP + "Desk.java", DESK.replace("    String hello(Order order) {", """
                    String pick(Order order, boolean formal) {
                        return formal ? order.label() : order.greeting();
                    }

                    String hello(Order order) {"""));
        files.put(SHOP + "Order.java", ORDER.replace("    public String line() {", """
                    String either(boolean formal, int kind) {
                        String s = formal ? status : greeting();
                        switch (kind) {
                            case 1: return greeting();
                            default: return s;
                        }
                    }

                    public String line() {"""));
        RenameFixture f = new RenameFixture(dir, files);

        assertThat(f.move("Order.greeting", "Customer")).startsWith("Moved Order.greeting() to ");

        assertThat(f.read(SHOP + "Order.java"))
                .contains("String s = formal ? status : this.customer.greeting();")
                .contains("case 1: return this.customer.greeting();");
        assertThat(f.read(SHOP + "Desk.java")).contains("return formal ? order.label() : order.customer.greeting();");
        assertThat(f.checkMove()).startsWith("Proven: it compiles, and Customer.greeting has the 6 references");
    }

    @Test
    void aMethodReferenceBecomesALambdaWhoseCallTakesTheRoute(@TempDir Path dir) throws IOException {
        Map<String, String> files = shop();
        files.put(
                SHOP + "Desk.java",
                DESK.replace("return order.greeting();", """
                java.util.function.Function<Order, String> f = Order::greeting;
                        return f.apply(order) + java.util.Optional.of(order).map(org.acme.shop.Order::label);""").replace("return order.label() + order.held();", """
                java.util.function.Supplier<String> s = order::label;
                        return s.get() + order.held();"""));
        files.put(SHOP + "Order.java", ORDER.replace("    public String line() {", """
                    java.util.function.Supplier<String> later() {
                        return this::label;
                    }

                    public String line() {"""));
        RenameFixture f = new RenameFixture(dir, files);

        assertThat(f.move("Order.label", "Customer"))
                .startsWith("Moved Order.label() to org.acme.shop.Customer: 3 uses ");

        assertThat(f.read(SHOP + "Desk.java"))
                .contains("java.util.Optional.of(order).map(order2 -> order2.customer.label(order2));")
                .contains("java.util.function.Supplier<String> s = () -> order.customer.label(order);");
        assertThat(f.read(SHOP + "Order.java")).contains("return () -> this.customer.label(this);");
        assertThat(f.checkMove()).startsWith("Proven: it compiles, and Customer.label has the 4 references");

        RenameFixture g = new RenameFixture(dir.resolve("greeting"), files);
        assertThat(g.move("Order.greeting", "Customer")).startsWith("Moved Order.greeting() to ");
        assertThat(g.read(SHOP + "Desk.java"))
                .contains("java.util.function.Function<Order, String> f = order2 -> order2.customer.greeting();");
        assertThat(g.checkMove()).startsWith("Proven: it compiles");
    }

    @Test
    void aMethodReferenceThatReadsItsReceiverOnceIsRefused(@TempDir Path dir) throws IOException {
        Map<String, String> files = shop();
        files.put(SHOP + "Desk.java", DESK.replace("    String hello(Order order) {", """
                    final Order kept = new Order(new Customer("kept"));

                    java.util.function.Supplier<String> later(Customer c) {
                        java.util.function.Supplier<String> a = kept::greeting;
                        return new Order(c)::greeting;
                    }

                    String hello(Order order) {"""));
        RenameFixture f = new RenameFixture(dir, files);

        assertThat(f.move("Order.greeting", "Customer"))
                .startsWith("Not moved, nothing is written")
                .contains("a method reference whose receiver a lambda would read at each call, not once  " + SHOP
                        + "Desk.java:7")
                .contains("a method reference whose receiver a lambda would read at each call, not once  " + SHOP
                        + "Desk.java:8");
        assertThat(f.read(SHOP + "Desk.java")).isEqualTo(files.get(SHOP + "Desk.java"));
    }

    @Test
    void anOuterInstanceBeforeNewIsPartOfTheReceiver(@TempDir Path dir) throws IOException {
        Map<String, String> files = RenameFixture.files(
                SHOP + "Customer.java", CUSTOMER, SHOP + "Order.java", """
                package org.acme.shop;

                public class Order {

                    public class Line {

                        int count = 1;

                        public String tag(Customer c) {
                            return c.name() + count;
                        }
                    }
                }
                """, SHOP + "Desk.java", """
                package org.acme.shop;

                class Desk {
                    String tag(Order order, Customer c) {
                        return order.new Line().tag(c);
                    }
                }
                """);
        RenameFixture f = new RenameFixture(dir, files);

        assertThat(f.move("Order.Line.tag", "Customer"))
                .startsWith("Moved Order.Line.tag() to ")
                .contains("  through p1, the parameter Customer c; a call reads c.tag(order.new Line())");

        assertThat(f.read(SHOP + "Desk.java")).contains("return c.tag(order.new Line());");
        assertThat(f.checkMove()).startsWith("Proven: it compiles, and Customer.tag has the 2 references");
    }

    @Test
    void inAnotherPackageTheSourceTypeIsImportedAndADocLinkNamesTheNewSignature(@TempDir Path dir) throws IOException {
        Map<String, String> files = RenameFixture.files(
                CRM + "Customer.java",
                CUSTOMER.replace("package org.acme.shop;", "package org.acme.crm;"),
                SHOP + "Order.java",
                """
                package org.acme.shop;

                import org.acme.crm.Customer;

                public class Order {

                    final Customer customer;

                    private final String status = "open";

                    public Order(Customer customer) {
                        this.customer = customer;
                    }

                    public String status() {
                        return status;
                    }

                    public String describe() {
                        return customer.name() + " " + status() + " " + this;
                    }
                }
                """,
                SHOP + "Desk.java",
                """
                package org.acme.shop;

                /** Prints {@link Order#describe()}. */
                class Desk {
                    String show(Order order) {
                        return order.describe();
                    }
                }
                """);
        RenameFixture f = new RenameFixture(dir, files);

        assertThat(f.move("Order.describe", "Customer"))
                .startsWith("Moved Order.describe() to org.acme.crm.Customer: 2 uses and 2 imports in 3 files");

        assertThat(f.read(CRM + "Customer.java"))
                .contains("import org.acme.shop.Order;")
                .contains("""
                            public String describe(Order order) {
                                return this.name() + " " + order.status() + " " + order;
                            }
                        """);
        assertThat(f.read(SHOP + "Desk.java"))
                .contains("import org.acme.crm.Customer;\n")
                .contains("/** Prints {@link Customer#describe(Order)}. */")
                .contains("return order.customer.describe(order);");
        assertThat(f.checkMove()).startsWith("Proven: it compiles, and Customer.describe has the 3 references");
    }

    @Test
    void anInheritedMemberAndALambdaCallerMoveThroughTheGetter(@TempDir Path dir) throws IOException {
        String model = "src/main/java/org/acme/model/";
        String system = "src/main/java/org/acme/system/";
        Map<String, String> files = RenameFixture.files(
                model + "Named.java",
                """
                package org.acme.model;

                public class Named {

                    private String name;

                    public String getName() {
                        return name;
                    }

                    public void setName(String name) {
                        this.name = name;
                    }
                }
                """,
                SHOP + "Kind.java",
                """
                package org.acme.shop;

                import org.acme.model.Named;

                public class Kind extends Named {
                }
                """,
                SHOP + "Pet.java",
                """
                package org.acme.shop;

                import org.acme.model.Named;

                public class Pet extends Named {

                    private Kind type;

                    public Kind getType() {
                        return this.type;
                    }

                    public String typeLabel() {
                        return getName() + " the " + this.type.getName();
                    }
                }
                """,
                SHOP + "Labels.java",
                """
                package org.acme.shop;

                import java.util.List;

                final class Labels {
                    static List<String> all(List<Pet> pets) {
                        return pets.stream().map(pet -> pet.typeLabel()).toList();
                    }

                    static List<String> sorted(List<Pet> pets) {
                        return pets.stream().map(Pet::typeLabel).sorted().toList();
                    }
                }
                """,
                system + "Report.java",
                """
                package org.acme.system;

                import org.acme.shop.Pet;

                /** One pet, labelled by {@link Pet#typeLabel()}. */
                public final class Report {
                    public static String line(Pet pet) {
                        return "- " + pet.typeLabel();
                    }
                }
                """);
        RenameFixture f = new RenameFixture(dir, files);

        assertThat(f.move("Pet.typeLabel", "Kind")).startsWith("Moved Pet.typeLabel() to org.acme.shop.Kind: ");

        assertThat(f.read(SHOP + "Kind.java")).contains("""
                    public String typeLabel(Pet pet) {
                        return pet.getName() + " the " + this.getName();
                    }
                """);
        assertThat(f.read(SHOP + "Labels.java"))
                .contains("map(pet -> pet.getType().typeLabel(pet)).toList()")
                .contains("map(pet -> pet.getType().typeLabel(pet)).sorted()");
        assertThat(f.read(system + "Report.java"))
                .contains("import org.acme.shop.Kind;")
                .contains("/** One pet, labelled by {@link Kind#typeLabel(Pet)}. */")
                .contains("return \"- \" + pet.getType().typeLabel(pet);");
        assertThat(f.checkMove()).startsWith("Proven: it compiles");
    }

    @Test
    void anOverrideIsRefusedAndWhatWouldChangeIsListed(@TempDir Path dir) throws IOException {
        Map<String, String> files = shop();
        files.put(CRM + "Ledger.java", """
                package org.acme.crm;

                public class Ledger {
                }
                """);
        RenameFixture f = new RenameFixture(dir, files);

        assertThat(f.move("Order.toString", "Customer"))
                .isEqualTo("Not moved, nothing is written: Order.toString() overrides or implements a supertype's"
                        + " method (@Override); moving it would change which method a call runs.");
        assertThat(f.move("Order.held", "Customer"))
                .startsWith("Not moved, nothing is written: moving Order.held() to org.acme.shop.Customer through f1"
                        + " would break or change:")
                .contains("synchronized: it would lock the target instead of Order");
        assertThat(f.move("Order.greeting", "org.acme.crm.Ledger"))
                .isEqualTo("Not moved, nothing is written: Order.greeting() is an instance method, and neither a"
                        + " field of Order nor a parameter of it holds a org.acme.crm.Ledger to call it on.");
        assertThat(f.read(SHOP + "Order.java")).isEqualTo(ORDER);
    }

    @Test
    void stateTheTargetWouldNotSeeIsAHazard(@TempDir Path dir) throws IOException {
        Map<String, String> files = RenameFixture.files(
                CRM + "Customer.java",
                CUSTOMER.replace("package org.acme.shop;", "package org.acme.crm;")
                        .replace("    int visits;", "    public int visits;"),
                SHOP + "Order.java",
                "package org.acme.shop;\n\nimport org.acme.crm.Customer;\n\n"
                        + ORDER.substring(ORDER.indexOf("public class")),
                SHOP + "Desk.java",
                "package org.acme.shop;\n\nimport org.acme.crm.Customer;\n\n"
                        + DESK.substring(DESK.indexOf("class Desk")));
        RenameFixture f = new RenameFixture(dir, files);

        assertThat(f.move("Order.label", "Customer"))
                .startsWith("Not moved, nothing is written: moving Order.label() to org.acme.crm.Customer through f1")
                .contains("uses Order.status, which is package-private and org.acme.crm.Customer would not see");
        assertThat(f.read(SHOP + "Order.java")).isEqualTo(files.get(SHOP + "Order.java"));
    }

    @Test
    void anInstanceMethodToTheClassItsTypeExtendsIsAPullUp(@TempDir Path dir) throws IOException {
        Map<String, String> files = shop();
        files.put(SHOP + "Rush.java", """
                package org.acme.shop;

                class Rush extends Order {
                    Rush(Customer c) {
                        super(c);
                    }

                    String urgent() {
                        return "!";
                    }
                }
                """);
        RenameFixture f = new RenameFixture(dir, files);

        assertThat(f.move("Rush.urgent", "Order"))
                .isEqualTo("Not moved, nothing is written: org.acme.shop.Order is the class org.acme.shop.Rush"
                        + " extends; a pull-up moves Rush.urgent() there (jk code pull-up, MCP pull_up).");
    }
}
