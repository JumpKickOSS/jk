package io.quarkus.test.junit;

/** Fixture stub: the type by its real name, so the fixture needs nothing on the classpath. */
public final class QuarkusMock {
    private QuarkusMock() {}

    public static <T> void installMockForType(T mock, Class<? super T> type) {}
}
