package fx.mp;

import io.helidon.microprofile.server.Server;

class Bad {
    Object start() {
        return Server.create().start();
    }
}
