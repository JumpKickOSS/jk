package fixture;

final class Bad {
    Object session(String requestLine) {
        return ProtoSession.sessionOf(requestLine, null);
    }
}
