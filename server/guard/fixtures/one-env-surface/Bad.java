package fx.env;

class Bad {
    String ci() {
        return System.getenv("CI");
    }
}
