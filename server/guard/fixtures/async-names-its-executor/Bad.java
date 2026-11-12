package fx.async;

import java.util.concurrent.CompletableFuture;

class Bad {
    CompletableFuture<String> probe() {
        return CompletableFuture.supplyAsync(() -> "free");
    }
}
