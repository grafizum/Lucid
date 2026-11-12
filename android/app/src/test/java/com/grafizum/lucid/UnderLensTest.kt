package com.grafizum.lucid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lens reads what it is on. These are the cases where a box says yes and
 * the glyphs say no — the padded row, the chat bubble, the paragraph — because
 * those are the ones that used to answer for text somewhere else on screen.
 */
class UnderLensTest {

    /** One line of 14sp type: 18px tall, sitting in the middle of a 64px row. */
    private val line = UnderLens.Box(left = 40, top = 100, right = 640, bottom = 118)
    private val row = UnderLens.Box(left = 0, top = 60, right = 1080, bottom = 124)

    @Test
    fun `on the line`() {
        assertTrue(UnderLens.holds(line, 200, 110))
        assertTrue(UnderLens.holds(line, 40, 100))
        assertTrue(UnderLens.holds(line, 640, 118))
    }

    @Test
    fun `in the padding of a tall row is not on its text`() {
        // Inside the row's bounds, nowhere near the words: the case the lens
        // used to answer anyway.
        assertTrue(UnderLens.holds(row, 200, 80))
        assertTrue(!UnderLens.holds(line, 200, 80))
        assertTrue(!UnderLens.holds(line, 200, 124))
    }

    @Test
    fun `a pixel of grace, then nothing`() {
        assertTrue(UnderLens.holds(line, 200, 119, slack = 3))
        assertTrue(!UnderLens.holds(line, 200, 122, slack = 3))
        assertTrue(UnderLens.holds(line, 643, 118, slack = 3))
        assertTrue(!UnderLens.holds(line, 646, 118, slack = 3))
    }

    @Test
    fun `innermost wins when boxes overlap`() {
        val picked = UnderLens.innermost(listOf(row, line), 200, 110)
        assertEquals(line, picked)
    }

    @Test
    fun `nothing under the lens is an answer`() {
        // Only lines are offered — a node's own box is never the test — so the
        // padding of a tall row reads as nothing at all.
        assertNull(UnderLens.innermost(listOf(line), 200, 80))
        assertNull(UnderLens.innermost(listOf(line), 900, 800))
    }

    @Test
    fun `slack never reaches across the screen`() {
        // A line 300px away stays unread even with the grace the callers use.
        val far = UnderLens.Box(left = 40, top = 400, right = 640, bottom = 418)
        assertNull(UnderLens.innermost(listOf(far), 200, 110, slack = 3))
    }
}
