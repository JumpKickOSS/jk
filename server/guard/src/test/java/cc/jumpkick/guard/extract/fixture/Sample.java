// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.extract.fixture;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** Fixture for the extractor: every fact category in one class. Never run. */
@SuppressWarnings("unused")
public final class Sample implements Supplier<String> {

    public static final String STEP = "compile-main";
    public static final int LIMIT = 3;
    static final String NOT_PUBLIC = "hidden";

    @Deprecated
    private String field;

    public Sample(@Marked String seed) {
        this.field = seed;
    }

    @Override
    public String get() {
        String os = System.getProperty("os.name");
        String arch = System.getProperty("os.arch");
        return os.toLowerCase(Locale.ROOT) + arch;
    }

    public static MessageDigest digest() throws NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256");
    }

    int branches(int x) {
        if (x > LIMIT) return 1;
        switch (x) {
            case 1 -> field = "a";
            case 2 -> field = "b";
            default -> field = "c";
        }
        try {
            return Integer.parseInt(field);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    Runnable lambda() {
        List<String> names = List.of("a");
        return () -> names.forEach(System.out::println);
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface Marked {}

    public class Inner {
        int read() {
            return field.length();
        }
    }
}
