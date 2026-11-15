package fx.retired;

class Bad {
    void parse(org.tomlj.TomlTable root) {
        if (root.contains("m2integration")) {
            throw new IllegalArgumentException("m2integration moved under [m2]; use integration");
        }
    }
}
