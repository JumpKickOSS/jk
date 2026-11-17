package fx;

// Reads every key WireKeyRules.EXEMPT names, as the tree does, so an exemption is never unread here.
class Foreign {
    void read(String line) {
        Jsonl.str(line, "java-options");
        Jsonl.str(line, "script-ref");
        Jsonl.str(line, "xsdDir");
        Jsonl.str(line, "defaultPackage");
        Jsonl.str(line, "bindingFiles");
        Jsonl.str(line, "auth");
        Jsonl.str(line, "usernameEnv");
        Jsonl.str(line, "passwordEnv");
        Jsonl.str(line, "registers");
        Jsonl.str(line, "specPath");
        Jsonl.str(line, "modelPackages");
        Jsonl.str(line, "generatesCode");
        Jsonl.str(line, "specArtifact");
    }
}
