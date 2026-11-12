package fx.executors;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

class Ok {
    Future<?> submit(ExecutorService pool, Runnable work) {
        return pool.submit(work);
    }
}
