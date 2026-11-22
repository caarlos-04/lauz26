package com.lauzhack.twin.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class LifeMapLayoutTest {
    @Test
    fun leavesGetOneRowEachAndParentsAreCentred() {
        val p = layoutLifeMap(setOf("", "A", "B", "AA", "AB", "B-"))
        assertEquals(0f, p.getValue("AA").row)
        assertEquals(1f, p.getValue("AB").row)
        assertEquals(2f, p.getValue("B-").row)
        assertEquals(0.5f, p.getValue("A").row)
        assertEquals(2f, p.getValue("B").row)          // a quiet turn does not split: the parent stays on its row
        assertEquals(1.25f, p.getValue("").row)
        assertEquals(2, p.getValue("B-").depth)
    }

    @Test
    fun fullBinaryTreeOfFiveTurnsHas32Rows() {
        val keys = mutableSetOf("")
        repeat(5) { keys += keys.filter { it.length == keys.maxOf { k -> k.length } }.flatMap { listOf(it + "A", it + "B") } }
        val p = layoutLifeMap(keys)
        assertEquals(63, p.size)
        assertEquals(31f, p.values.maxOf { it.row })
        assertEquals(15.5f, p.getValue("").row)
    }
}
