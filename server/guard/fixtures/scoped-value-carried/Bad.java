package fx.scoped;

class Bad {
    private static final ScopedValue<String> LABEL = ScopedValue.newInstance();
}
