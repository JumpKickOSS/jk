package fx.threads;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

class Ok {
    Future<?> pump(ExecutorService pool, Runnable body) {
        return pool.submit(body);
    }
}
