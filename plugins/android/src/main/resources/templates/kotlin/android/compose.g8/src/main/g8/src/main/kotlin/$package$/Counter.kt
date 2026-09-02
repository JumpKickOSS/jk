package $package$

/** Plain-JVM state holder: the part `jk test` exercises without a device. */
class Counter {
    fun increment(current: Int): Int = current + 1

    fun label(taps: Int): String =
        when (taps) {
            0 -> "Tap the button"
            1 -> "1 tap"
            else -> taps.toString() + " taps"
        }
}
