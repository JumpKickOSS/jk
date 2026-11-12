package fx.async;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

class Ok {
    CompletableFuture<String> probe(Executor pool) {
        return CompletableFuture.supplyAsync(() -> "free", pool);
    }
}
