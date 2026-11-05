package $package$;

public final class Main {

    public static void main(String[] args) {
        System.out.println(greeting(args));
    }

    /** The line {@code main} prints: a greeting for the names given, or for the program itself. */
    static String greeting(String... names) {
        if (names.length == 0) return "Hello from $name$";
        return "Hello, " + String.join(" and ", names) + "!";
    }

    private Main() {}
}
