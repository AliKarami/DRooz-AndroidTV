package com.alikarami.drooz.player.session

import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

class SessionListenerFilterTest {
    private val calls = mutableListOf<Pair<Method, Array<Any?>>>()
    private val target = Proxy.newProxyInstance(
        Player.Listener::class.java.classLoader, arrayOf(Player.Listener::class.java),
    ) { _, m, args -> calls += m to (args ?: emptyArray()); null } as Player.Listener

    private val ours = Player.Commands.Builder().add(Player.COMMAND_PLAY_PAUSE).build()
    private val ourMetadata = MediaMetadata.Builder().setTitle("ours").build()
    private val filter = SessionListenerFilter(target, { ours }, { ourMetadata })

    private fun defaultArg(type: Class<*>): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> true
        Int::class.javaPrimitiveType -> 3
        Long::class.javaPrimitiveType -> 7L
        Float::class.javaPrimitiveType -> 0.5f
        else -> null
    }

    // Kotlin `by` delegation skips Java default methods: the session then heard none of play / pause / seek.
    @Test fun everyListenerEventReachesTheSession() {
        val methods = Player.Listener::class.java.methods.filter { !Modifier.isStatic(it.modifiers) }
        for (m in methods) {
            calls.clear()
            val args = m.parameterTypes.map(::defaultArg).toTypedArray()
            m.invoke(filter, *args)
            assertEquals("${m.name} not forwarded", 1, calls.size)
            val (got, gotArgs) = calls.single()
            assertEquals(m.name, got.name)
            assertEquals(m.parameterTypes.toList(), got.parameterTypes.toList())
            when (m.name) {
                "onAvailableCommandsChanged" -> assertSame(ours, gotArgs.single())
                "onMediaMetadataChanged" -> assertSame(ourMetadata, gotArgs.single())
                else -> assertEquals("${m.name} args", args.toList(), gotArgs.toList())
            }
        }
    }
}
