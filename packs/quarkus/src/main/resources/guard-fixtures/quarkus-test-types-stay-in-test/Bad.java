package fx.qtest;

import io.quarkus.test.junit.QuarkusMock;

class Bad {
    void wire() {
        QuarkusMock.installMockForType(new Object(), Object.class);
    }
}
