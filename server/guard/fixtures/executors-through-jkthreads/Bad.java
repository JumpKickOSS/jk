package fx.executors;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

class Bad {
    ExecutorService pool() {
        return Executors.newFixedThreadPool(4);
    }
}
