package fx.truth;

class Bad {
    boolean on(String raw) {
        return "true".equals(raw) || raw.equalsIgnoreCase("yes");
    }
}
