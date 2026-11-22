package com.lauzhack.twin.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class LifeMapLayoutTest {
    @Test
    fun leavesGetOneRowEachAndParentsAreCentred() {
        val p = layoutLifeMap(setOf("", "A-master", "NOTHING", "A-master/A-loan", "A-master/NOTHING", "NOTHING/NOTHING"))
        assertEquals(0f, p.getValue("A-master/A-loan").row)
        assertEquals(1f, p.getValue("A-master/NOTHING").row)
        assertEquals(2f, p.getValue("NOTHING/NOTHING").row)
        assertEquals(0.5f, p.getValue("A-master").row)
        assertEquals(2f, p.getValue("NOTHING").row)          // one child: the parent stays on its row
        assertEquals(1.25f, p.getValue("").row)
        assertEquals(2, p.getValue("NOTHING/NOTHING").depth)
    }

    @Test
    fun onlyTheExploredNodesAreLaidOut() {
        // Three actions at the start, then one decision each: 3 lives, 7 nodes.
        val keys = setOf("", "A", "B", "C", "A/NOTHING", "B/NOTHING", "C/NOTHING")
        val p = layoutLifeMap(keys)
        assertEquals(7, p.size)
        assertEquals(2f, p.values.maxOf { it.row })
        assertEquals(1f, p.getValue("").row)
    }
}
