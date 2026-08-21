package $package$;

import io.quarkus.runtime.Quarkus;

/**
 * Optional process entry. Prefer a plain {@code main} over {@code @QuarkusMain} so
 * {@code @QuarkusTest} bootstrap does not double-index the class under jk's
 * {@code target/classes/main} layout (Quarkus then reports two mains with the same name).
 */
public class Application {

    public static void main(String... args) {
        Quarkus.run(args);
    }
}
