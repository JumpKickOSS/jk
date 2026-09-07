package fx.sleep;

class Bad {
    void pause(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }
}
