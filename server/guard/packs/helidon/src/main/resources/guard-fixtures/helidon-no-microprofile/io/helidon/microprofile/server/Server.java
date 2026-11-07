package io.helidon.microprofile.server;

/** Fixture stub: the type by its real name, so the fixture needs nothing on the classpath. */
public interface Server {
    static Server create() {
        throw new UnsupportedOperationException();
    }

    Server start();
}
