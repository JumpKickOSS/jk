package fx.truth;

class Ok {
    boolean on(String raw) {
        return raw.strip().length() > 3;
    }
}
