package com.tencent.kuikly.compose.ui.node

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CHANGES.md §54: the draw pass with [DrawMarks] leaves every view with exactly the props the
 * upstream pass (one flag, every ancestor redrawn) leaves it with, frame by frame, and runs no more
 * own draws than it.
 *
 * A node's props are a function of what its own draw read (`KNode.draw`: reset, its layers and draw
 * modifiers, flush; `KuiklyCanvas` carries state only inside a `CanvasView`'s own pass), so "same
 * props" is "the same state version drawn last". Each model node counts versions: a change bumps
 * it, an own draw records the version it drew. The two passes below are `KNode.draw` before and
 * after §54, over the same random trees, changes and alpha-0 layers.
 */
class DrawMarksTest {

    private class Node(val parent: Node?) {
        val children = mutableListOf<Node>()
        var placed = true
        var alphaZero = false // one of its layers at alpha <= 0: its draw does not reach its children
        var version = 0

        // Upstream: one flag.
        var legacyDirty = true
        var legacyDrawn = -1
        var legacyDraws = 0

        // §54.
        val marks = DrawMarks()
        var drawn = -1
        var draws = 0
        var visits = 0

        fun transparentUpward(): Boolean = alphaZero || (parent?.transparentUpward() ?: false)
    }

    // ── Upstream KNode.draw / invalidateDraw ──
    private fun legacyInvalidate(n: Node) {
        if (!n.legacyDirty) {
            n.legacyDirty = true
            n.parent?.let { legacyInvalidate(it) }
        }
    }

    private fun legacyDraw(n: Node) {
        if (!n.legacyDirty) return
        n.legacyDirty = false
        n.legacyDrawn = n.version
        n.legacyDraws++
        if (!n.alphaZero) n.children.filter { it.placed }.forEach { legacyDraw(it) }
    }

    // ── §54 KNode.draw / invalidateDraw ──
    private fun invalidate(n: Node) {
        if (n.marks.markSelf()) n.parent?.let { tellAncestor(it) }
    }

    private fun tellAncestor(n: Node) {
        if (n.marks.markDescendant()) n.parent?.let { tellAncestor(it) }
    }

    private fun draw(n: Node) {
        when (n.marks.take()) {
            DrawAction.Draw -> {
                n.drawn = n.version
                n.draws++
                if (!n.alphaZero) n.children.filter { it.placed }.forEach { draw(it) }
            }
            DrawAction.Visit -> {
                n.visits++
                if (!n.transparentUpward()) n.children.filter { it.placed }.forEach { draw(it) }
            }
            DrawAction.Skip -> Unit
        }
    }

    private fun change(n: Node) {
        n.version++
        legacyInvalidate(n)
        invalidate(n)
    }

    private fun tree(random: Random): List<Node> {
        val all = mutableListOf(Node(null))
        repeat(random.nextInt(8, 40)) {
            val parent = all[random.nextInt(all.size)]
            if (depth(parent) < 6) all += Node(parent).also { parent.children += it }
        }
        return all
    }

    private fun depth(n: Node): Int = generateSequence(n) { it.parent }.count()

    private fun frame(root: Node) {
        legacyDraw(root)
        draw(root)
    }

    @Test
    fun everyViewEndsEachFrameWithTheUpstreamPassProps() {
        val random = Random(48)
        repeat(400) { seed ->
            val nodes = tree(random)
            val root = nodes[0]
            frame(root)
            repeat(30) {
                repeat(random.nextInt(0, 5)) {
                    val n = nodes[random.nextInt(nodes.size)]
                    when (random.nextInt(10)) {
                        // An alpha crossing 0 is a layer change of that node: its own draw runs.
                        0 -> { n.alphaZero = !n.alphaZero; change(n) }
                        1 -> if (n.parent != null) { n.placed = !n.placed; change(n.parent) }
                        else -> change(n)
                    }
                }
                frame(root)
                for (n in nodes) {
                    assertEquals(n.legacyDrawn, n.drawn, "tree $seed: a view's last drawn state differs")
                }
            }
            val legacy = nodes.sumOf { it.legacyDraws }
            val now = nodes.sumOf { it.draws }
            assertTrue(now <= legacy, "tree $seed: more own draws ($now) than upstream ($legacy)")
        }
    }

    @Test
    fun aChangeDeepInATreeDrawsOnlyThatNode() {
        val root = Node(null)
        val a = Node(root).also { root.children += it }
        val b = Node(a).also { a.children += it }
        val leaf = Node(b).also { b.children += it }
        val sibling = Node(a).also { a.children += it }
        frame(root)
        listOf(root, a, b, leaf, sibling).forEach { it.draws = 0; it.visits = 0; it.legacyDraws = 0 }
        repeat(60) {
            change(leaf)
            frame(root)
        }
        assertEquals(60, leaf.draws)
        assertEquals(listOf(0, 0, 0, 0), listOf(root, a, b, sibling).map { it.draws })
        assertEquals(listOf(60, 60, 60), listOf(root, a, b).map { it.visits })
        // Upstream redrew the whole chain every frame.
        assertEquals(listOf(60, 60, 60, 60, 0), listOf(root, a, b, leaf, sibling).map { it.legacyDraws })
    }

    @Test
    fun aChangeUnderATransparentParentWaitsAsUpstreamDid() {
        val root = Node(null)
        val parent = Node(root).also { root.children += it }
        val child = Node(parent).also { parent.children += it }
        frame(root)
        parent.alphaZero = true
        change(parent)
        frame(root)
        change(child)
        frame(root)
        assertEquals(child.legacyDrawn, child.drawn)
        assertTrue(child.drawn < child.version, "neither pass draws a child its parent's draw does not reach")
        parent.alphaZero = false
        change(parent)
        frame(root)
        assertEquals(child.version, child.drawn)
        assertEquals(child.legacyDrawn, child.drawn)
    }

    @Test
    fun marksAreConsumedInTheOrderThePassNeedsThem() {
        val marks = DrawMarks()
        assertEquals(DrawAction.Draw, marks.take()) // new nodes start marked
        assertEquals(DrawAction.Skip, marks.take())
        assertTrue(marks.markDescendant())
        assertTrue(!marks.markSelf()) // already on a marked path: the parent knows
        assertEquals(DrawAction.Draw, marks.take())
        assertEquals(DrawAction.Skip, marks.take())
        assertTrue(marks.markDescendant())
        assertTrue(!marks.markDescendant())
        assertEquals(DrawAction.Visit, marks.take())
        assertEquals(DrawAction.Skip, marks.take())
    }
}
