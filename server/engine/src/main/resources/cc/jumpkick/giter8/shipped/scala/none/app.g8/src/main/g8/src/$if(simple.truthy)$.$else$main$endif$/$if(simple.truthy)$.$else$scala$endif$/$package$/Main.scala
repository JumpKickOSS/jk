package $package$

/** The application's entry point. */
object Main:

    def main(args: Array[String]): Unit = println(greeting)

    /** The line `main` prints. */
    def greeting: String = "Hello from $name$"
