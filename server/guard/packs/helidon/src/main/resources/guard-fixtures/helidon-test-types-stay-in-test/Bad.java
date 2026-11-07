package fx.htest;

import io.helidon.webserver.testing.junit5.DirectClient;

class Bad {
    Object client() {
        return DirectClient.class;
    }
}
