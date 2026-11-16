# Hidden: copied in after the agent. ServiceLoader still finds the renamed class.
grep -qx "com.acme.ledger.Diary" src/main/resources/META-INF/services/com.acme.ledger.Sink
