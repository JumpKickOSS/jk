package fx.soft;

import java.lang.ref.SoftReference;

class Bad {
    SoftReference<byte[]> cache = new SoftReference<>(new byte[0]);
}
