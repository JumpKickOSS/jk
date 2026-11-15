package fixture;

final class Ok {
    // ProtoSession.sessionOf( in a comment is not a call.
    Object session(String requestLine) {
        return ReleaseSession.sessionOf(requestLine, null);
    }
}
