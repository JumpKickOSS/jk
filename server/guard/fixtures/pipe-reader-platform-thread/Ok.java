package fx.pipe;

import java.io.IOException;
import java.io.OutputStream;

class Ok {
    Thread pump(Process child, OutputStream captured) {
        return Thread.ofPlatform().daemon().name("pump").start(() -> {
            try {
                child.getInputStream().transferTo(captured);
            } catch (IOException gone) {
                // the child is gone
            }
        });
    }
}
