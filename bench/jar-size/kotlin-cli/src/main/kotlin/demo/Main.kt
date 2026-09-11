// SPDX-License-Identifier: Apache-2.0
package demo

import kotlin.reflect.full.memberProperties
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.ByteString.Companion.encodeUtf8

/** Kotlin CLI fixture: stdlib, reflect, coroutines and okio, each carrying a `.kotlin_module`. */
data class Greeting(val who: String, val digest: String)

fun main(args: Array<String>) = runBlocking {
    val who = args.firstOrNull() ?: "world"
    val digest = async { Buffer().writeUtf8(who).readByteString().sha256().hex() }
    val greeting = Greeting(who, digest.await())
    for (property in Greeting::class.memberProperties) {
        println("${property.name} = ${property.get(greeting)}")
    }
    println("hello, ${who.encodeUtf8().utf8()}")
}
