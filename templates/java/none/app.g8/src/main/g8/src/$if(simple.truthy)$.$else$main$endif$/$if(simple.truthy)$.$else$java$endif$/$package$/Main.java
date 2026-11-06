package $package$;

public final class Main {

    public static void main(String[] args) {
        System.out.println(greeting());
    }

    /** The line {@code main} prints. */
    static String greeting() {
        return "Hello from $name$";
    }

    private Main() {}
}
