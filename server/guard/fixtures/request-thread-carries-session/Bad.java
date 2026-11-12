package fx.threads;

class Bad {
    Thread pump(Runnable body) {
        return Thread.ofVirtual().name("jk-worker-pump").start(body);
    }
}
