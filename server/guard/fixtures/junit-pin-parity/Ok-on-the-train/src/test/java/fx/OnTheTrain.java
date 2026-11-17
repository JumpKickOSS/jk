package fx;

// A fixture manifest that pins the train at the locked version, and one pin that is its fixture's point.
class OnTheTrain {
    static final String MANIFEST = """
            [test-dependencies]
            junit-jupiter = { group = "org.junit.jupiter", name = "junit-jupiter", version = "6.1.3" }
            """;
    static final String CONFLICT = """
            junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "1.9.0" }  # off the train: provokes the conflict
            """;
}
