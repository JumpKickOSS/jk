package javax.persistence;

/** Fixture stub: the type by its real name, so the fixture needs nothing on the classpath. */
public final class Persistence {
    private Persistence() {}

    public static Object createEntityManagerFactory(String unit) {
        return unit;
    }
}
