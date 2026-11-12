package com.grafizum.lucid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the rest of the screen is cut down and how its answer is read back.
 * Nothing here may guess: a neighbour that does not line up means the line is
 * translated on its own.
 */
class ContextsTest {

    private val line = "彼は来ます？"
    private val screen = """
        Hey, are you joining us tomorrow?
        Where are you now?
        I'm at the station.
        彼は来ます？
        Cool.
    """.trimIndent()

    @Test
    fun `no screen, no context — the line alone`() {
        assertEquals(listOf(line), Contexts.query("", line))
        assertEquals(listOf(line), Contexts.query("   \n", line))
    }

    @Test
    fun `the line under the lens goes first, then the screen`() {
        val sent = Contexts.query(screen, line)
        assertEquals(line, sent.first())
        assertTrue(sent.contains("Where are you now?"))
        // The line is already on its own line; it must not travel twice.
        assertEquals(1, sent.count { it == line })
    }

    @Test
    fun `repeated rows collapse`() {
        val sent = Contexts.query("Delivered\nDelivered\nRead\nRead\nSeen", line)
        assertEquals(listOf(line, "Delivered", "Read", "Seen"), sent)
    }

    @Test
    fun `the context is capped, and a single huge row cannot eat the budget`() {
        val many = (1..40).joinToString("\n") { "line $it" }
        val sent = Contexts.query(many, line)
        assertEquals(Contexts.MAX_LINES + 1, sent.size)
        assertEquals(line, sent.first())

        val giant = "x".repeat(5000)
        val capped = Contexts.query(giant, line).map { it.length }
        assertTrue(capped.all { it <= Contexts.MAX_LINE })
    }

    @Test
    fun `answers are only taken when they come back line for line`() {
        val sent = listOf(line, "Cool.", "See you.")
        assertEquals("Will he come?", Contexts.resolve(sent, "Will he come?\nCool.\nSee you."))
        // One short, one extra, one blank: all refused.
        assertNull(Contexts.resolve(sent, "Will he come?\nCool."))
        assertNull(Contexts.resolve(sent, "Will he come?\nCool.\nSee you.\nMore."))
        assertNull(Contexts.resolve(sent, "\nCool.\nSee you."))
    }

    @Test
    fun `a single line takes the whole answer as before`() {
        assertEquals("Will he come?", Contexts.resolve(listOf(line), "  Will he come?  "))
        assertEquals("one\ntwo", Contexts.resolve(listOf(line), "one\ntwo"))
    }
}
