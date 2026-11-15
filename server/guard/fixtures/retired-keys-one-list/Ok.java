package fx.retired;

class Ok {
    void parse(org.tomlj.TomlTable root) {
        if (root.contains("m2") && !root.isTable("m2")) {
            throw new IllegalArgumentException("`m2` must be a table; use [m2] with an integration key");
        }
    }
}
