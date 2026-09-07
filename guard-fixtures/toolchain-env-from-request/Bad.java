package fx.env;

class Bad {
    String jdk() {
        return System.getenv("JK_JDK");
    }
}
