/*
 * Omega-Plugin: a realtime guitar effects processor.
 * Copyright (C) 2026  Sam Ganji <sam.ganji.n@gmail.com>
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.example.ui

import org.example.dsp.AudioProcessor
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseEvent
import java.awt.geom.CubicCurve2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.QuadCurve2D
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.Scrollable
import javax.swing.SwingUtilities
import javax.swing.event.ChangeListener
import kotlin.math.cos
import kotlin.math.sin

/**
 * The pedal board: the pedals in signal order, patched together with cables.
 *
 * The board **reflows**. Wide, it is one row; narrow it and the pedals wrap into a grid, and
 * narrower still into a single column. The window's shape chooses the layout — there is no mode to
 * switch.
 *
 * Wrapped rows alternate direction, so the chain snakes: row one runs left to right, row two right
 * to left, and so on. That is what keeps the cables short. Routing every row left-to-right would
 * need a return cable dragged all the way back across the board between each pair, which is both
 * ugly and genuinely hard to read; the snake turns every wrap into a short vertical hop at the end
 * where the signal already is. It is also how anyone wires a real two-row pedal board.
 *
 * Positions are computed here rather than delegated to a `LayoutManager`, because the cable routing
 * and the drag hit-testing both need to know which row a pedal ended up in and which way that row
 * flows.
 */
class ChainBoard(effects: List<AudioProcessor>) : JPanel(null), Scrollable {

    /** Fired with the new signal order once a drag settles. */
    var onReorder: ((List<AudioProcessor>) -> Unit)? = null

    /**
     * Set while the stream is running.
     *
     * Re-ordering is blocked rather than merely discouraged: [org.example.dsp.EffectChain.reorder]
     * publishes atomically and would not crash, but a block that ran through a changed chain would
     * be audible, and delay lines and reverb tails carried into a new position would not be.
     */
    var locked: Boolean = false
        set(value) {
            field = value
            applyCursors()
            repaint()
        }

    private val panels = effects.map { EffectPanel(it) }.toMutableList()
    private var dragIndex = -1

    // ------------------------------------------------------- hiding what is off

    /**
     * Whether bypassed pedals are kept off the board.
     *
     * A **view** state and nothing more. A hidden pedal is still in the chain, still in the preset
     * and still in the order — it is simply not drawn, and since the only pedals that qualify are
     * the ones the chain already skips, hiding one cannot change a sound. That is the whole reason
     * this is safe to do while the stream runs, unlike every other change to the board.
     */
    var hideDisabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            eye.kind = if (value) {
                EffectPanel.IconButton.Kind.EYE_CLOSED
            } else {
                EffectPanel.IconButton.Kind.EYE
            }
            eye.toolTipText = if (value) {
                "Show the pedals that are switched off"
            } else {
                "Hide the pedals that are switched off"
            }
            // Setting the eye either way is an unambiguous statement about every pedal on the
            // board, so nothing stays exempt from it.
            exempt.clear()
            refreshVisibility()
            onHideDisabledChanged?.invoke(value)
        }

    /** Fired when the eye is pressed, so the choice can be remembered across runs. */
    var onHideDisabledChanged: ((Boolean) -> Unit)? = null

    /** Currently being faded out, or already gone. Excluded from [wrap] and from the cable run. */
    private var concealed: Set<EffectPanel> = emptySet()

    /**
     * Pedals added since the eye was last pressed, which are never concealed.
     *
     * Without this, pressing `+` while pedals are hidden adds an FX slot — which starts switched
     * off, which is precisely the thing being hidden — and it vanishes on arrival. The user pressed
     * a button and nothing happened. A pedal leaves the exemption as soon as its own switch is
     * touched, because at that point what should happen to it is no longer ambiguous.
     */
    private val exempt = mutableSetOf<EffectPanel>()

    /** True while any pedal's opacity is still travelling, so [doLayout] eases rather than snaps. */
    private var fading = false

    /**
     * True while the board is rearranging itself after a change it did not animate into — a preset
     * load that reorders the chain, or a slot arriving or leaving.
     *
     * The same purpose [settling] serves after a drop, kept separate because that one is also what
     * tells [step] to watch the lift easing back down. Cleared by [step] once nothing is moving.
     */
    private var shuffling = false

    /**
     * Pedals that have left the chain but are still on screen, fading out.
     *
     * They are out of [panels] the instant they are removed, and that is the whole point: [order]
     * feeds `EffectChain.replaceAll`, so a pedal that lingered in the list while it faded would keep
     * processing audio after the user deleted it. Holding the component here instead keeps the
     * picture honest — the chain is already rewired — while the gap closes over about 150 ms.
     */
    private val ghosts = mutableListOf<EffectPanel>()

    private val eye = EffectPanel.IconButton(EffectPanel.IconButton.Kind.EYE) { toggleHideDisabled() }
        .apply { toolTipText = "Hide the pedals that are switched off" }

    /**
     * The header lane's two ends. Whatever the window adds here is pinned to the top of the board,
     * beside the eye on the left and against the far edge on the right, and stays in view when the
     * board scrolls — the same treatment the eye has always had.
     *
     * The right end grows the lane to fit what it holds, **whether or not it is showing**. It holds
     * the LEVEL knob, which is only seen in split mode, and the lane used to be sized for it only
     * then — so switching split on pushed every row of pedals down to make room for the knob, and
     * switching it off pulled them back up. The room is kept from the start now and the knob fades
     * into it; see [showSplitControls].
     */
    val headerLeft: JPanel = JPanel(FlowLayout(FlowLayout.LEFT, HEADER_GAP, 0)).apply { backed() }
    val headerRight: FadingPanel = FadingPanel(FlowLayout(FlowLayout.RIGHT, HEADER_GAP, 0)).apply {
        background = Theme.BACKGROUND // its own backing, painted by hand — see FadingPanel
        alpha = 0f
        isVisible = false
    }

    /**
     * The part of the left end that exists only in split mode — the window puts the board's name
     * there, and adds it to [headerLeft] itself, after its own controls, so the name sits at the end
     * of the row. Fades with [headerRight], so everything split adds to a header arrives together.
     */
    val splitOnly: EffectPanel.FadeRow =
        EffectPanel.FadeRow(FlowLayout(FlowLayout.LEFT, HEADER_GAP, 0)).apply {
            alpha = 0f
            isVisible = false
        }

    /** True while the split-only controls are meant to be there; false while they fade out. */
    private var splitControls = false

    private val headerAnimator = javax.swing.Timer(FRAME_MS) { stepHeader() }.apply { isCoalesce = true }

    /**
     * Fades the split-only header controls in or out.
     *
     * In: made visible at once, so they can be used before they have fully arrived, and then
     * dissolved in. Out: dissolved first and made invisible only once gone, so an invisible knob never
     * takes a click. Neither changes the lane, which was sized for them all along.
     */
    fun showSplitControls(on: Boolean) {
        if (splitControls == on) return
        splitControls = on
        if (on) {
            headerRight.isVisible = true
            splitOnly.isVisible = true
            headerLeft.revalidate()
            placeHeader()
        }
        headerAnimator.start()
    }

    private fun stepHeader() {
        val target = if (splitControls) 1f else 0f
        val next = if (kotlin.math.abs(target - headerRight.alpha) < 0.01f) {
            target
        } else {
            headerRight.alpha + (target - headerRight.alpha) * EASE
        }
        headerRight.alpha = next
        splitOnly.alpha = next
        if (next == target) {
            headerAnimator.stop()
            if (!splitControls) {
                headerRight.isVisible = false
                splitOnly.isVisible = false
                headerLeft.revalidate()
                placeHeader()
            }
        }
        headerLeft.repaint()
        headerRight.repaint()
    }

    /**
     * Opaque in the board's own background colour. Invisible while the header sits in its lane, and
     * what keeps the controls legible when the board has scrolled a row of pedals up underneath
     * them — see [raiseHeader] for the other half of that.
     */
    private fun JPanel.backed() {
        isOpaque = true
        background = Theme.BACKGROUND
    }

    /**
     * Opacity of the whole board, 0 to 1, for the second board arriving and leaving in split mode.
     *
     * Applied in [paint] so the pedals, the cables and the header all go together; see
     * [EffectPanel.fade] for why `paintComponent` would not do.
     */
    var alpha: Float = 1f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (field == clamped) return
            field = clamped
            repaint()
        }

    private fun toggleHideDisabled() {
        hideDisabled = !hideDisabled
    }

    /**
     * Recomputes what should be on the board, and starts the animation that gets it there.
     *
     * Called when the eye is pressed and whenever a bypass switch moves, so switching a pedal off
     * while hiding is on makes it leave rather than sit there contradicting the setting.
     */
    fun refreshVisibility() {
        val next = if (hideDisabled) {
            // `mutesWhenDisabled` never appears on the board today — the master is not a pedal — but
            // an effect whose switch silences rather than bypasses is still doing something when it
            // is off, and hiding it would be hiding a working module.
            panels.filter { it !in exempt && !it.effect.enabled && !it.effect.mutesWhenDisabled }
                .toSet()
        } else {
            emptySet()
        }
        if (next == concealed) return
        concealed = next
        // Anything coming back has to be a component again before it can be seen to fade in.
        for (panel in panels) {
            if (panel !in concealed && !panel.isVisible) {
                panel.isVisible = true
                panel.fade = 0f
            }
        }
        fading = true
        revalidate()
        animator.start()
    }

    private fun shown(): List<EffectPanel> = panels.filter { it !in concealed }

    /** Pedals grouped by row, in chain order. Rebuilt by [doLayout]; read by painting and hit-testing. */
    private var rows: List<List<EffectPanel>> = listOf(panels.toList())

    // ------------------------------------------------------- drag animation

    /** Where each pedal belongs. Actual bounds ease towards these; see [step]. */
    private val slots = LinkedHashMap<EffectPanel, Rectangle>()

    private var dragged: EffectPanel? = null

    /** Where in the pedal the mouse went down, so it does not jump under the cursor on pick-up. */
    private var grab = Point()

    /** Cursor position in board coordinates. */
    private var pointer = Point()

    /** Pick-up progress, 0 flat to 1 fully lifted. Animated so the lift eases rather than snaps. */
    private var lift = 0f

    /** True between releasing the mouse and the pedal finishing its descent into its slot. */
    private var settling = false

    private val animator = javax.swing.Timer(FRAME_MS) { step() }.apply { isCoalesce = true }

    /** Current signal order, left to right and then down. */
    val order: List<AudioProcessor> get() = panels.map { it.effect }

    /** Fired when the user changes a knob or a bypass switch on any pedal. */
    var onEdited: (() -> Unit)? = null

    /**
     * Applies an order decided elsewhere — a preset load. Ignored unless [effects] is a permutation
     * of the pedals on the board, which keeps a stale preset from emptying it.
     *
     * Animated, for the same reason a drag is: the eye is far better at following a pedal that
     * travels than at spotting that a static picture is not the one it was looking at a frame ago.
     * [computeSlots] is called here rather than left to the `revalidate` because `revalidate` defers
     * layout to the event queue — [step] would otherwise run first, find every pedal already sitting
     * on its stale slot, conclude nothing is moving and stop the animator before [doLayout] ever ran.
     * [beginDrag] computes its slots up front for exactly the same reason.
     */
    fun setOrder(effects: List<AudioProcessor>) {
        val byEffect = panels.associateBy { it.effect }
        val reordered = effects.mapNotNull { byEffect[it] }
        if (reordered.size != panels.size) return
        panels.clear()
        panels.addAll(reordered)
        // Nothing to animate from before the board has been laid out — at session restore the pedals
        // have no bounds yet, and there arriving in the right place is the correct behaviour.
        if (width > 0) {
            shuffling = true
            computeSlots()
            animator.start()
        }
        revalidate()
        repaint()
    }

    /** Pushes every effect's state back into its controls, after a preset load. */
    fun syncFromEffects() {
        panels.forEach { it.syncFromEffect() }
        // A preset decides which pedals are switched off, so it decides which ones belong on screen.
        refreshVisibility()
    }

    /** The pedal showing [effect], for wiring up controls that only one module has. */
    fun panelFor(effect: AudioProcessor): EffectPanel? = panels.firstOrNull { it.effect === effect }

    init {
        isOpaque = false
        panels.forEach { attach(it) }
        headerLeft.add(eye)
        add(headerLeft)
        add(headerRight)
        applyCursors()
    }

    private fun attach(panel: EffectPanel) {
        add(panel)
        panel.onDragStart = { p, e -> beginDrag(p, e) }
        panel.onDragMove = { p, e -> continueDrag(p, e) }
        panel.onDragEnd = { endDrag() }
        panel.onEdited = {
            onEdited?.invoke()
            // A bypass switch is the one edit that can change what belongs on the board, and it also
            // settles what should happen to a pedal that arrived exempt.
            exempt.remove(panel)
            if (hideDisabled) refreshVisibility()
        }
        // A pedal that changed width changes which pedals fit on a row, and the wrap is computed
        // here rather than by a layout manager, so nothing else would notice.
        panel.onResized = {
            revalidate()
            repaint()
        }
    }

    /**
     * Puts a new pedal on the board, immediately after [after] in signal order.
     *
     * Only ever called with the stream stopped — see [locked]. Adding to a live chain would mean
     * publishing an effect that has not been through `prepare` or the warm-up, which is the pair of
     * things the threading contract is most explicit about.
     */
    fun addPanel(effect: AudioProcessor, after: AudioProcessor?): EffectPanel {
        val panel = EffectPanel(effect)
        attach(panel)
        val index = panels.indexOfFirst { it.effect === after }
        if (index >= 0) panels.add(index + 1, panel) else panels.add(panel)
        // See [exempt]: a new FX slot starts switched off, which is the state being hidden.
        if (hideDisabled) exempt += panel
        applyCursors()
        // Same guard as [setOrder]: with no width there is no slot to arrive at, so it simply
        // appears with the rest of the board at first layout.
        if (width > 0) {
            // It **fades up where it will live**, rather than sliding in from anywhere. A pedal that
            // has never been on the board has no previous position, so any movement would be an
            // invention — and an invented slide reads as "this came from over there", which is a
            // claim about the chain that is not true. Its neighbours do move, sliding apart to open
            // the gap it lands in, and that is what carries the sense of it having been inserted.
            panel.fade = 0f
            shuffling = true
            computeSlots()
            slots[panel]?.let {
                panel.bounds = it
                // Bounds set outside a layout pass invalidate without scheduling one; see [step].
                panel.validate()
            }
            animator.start()
        }
        revalidate()
        repaint()
        return panel
    }

    /**
     * Takes a pedal off the board. Silently does nothing if it was not on it.
     *
     * It leaves [panels] **now**, not when the fade finishes, and that ordering is not negotiable:
     * `MainWindow.deleteSlot` and `syncSlots` both call `EffectChain.replaceAll(allEffects())` on
     * the next line, `allEffects()` reads [order], and [order] reads [panels] — a pedal still in the
     * list would be published straight back into the chain and go on processing audio after the user
     * deleted it. The component moves to [ghosts] instead, where it is nothing but a picture.
     */
    fun removePanel(effect: AudioProcessor) {
        val panel = panels.firstOrNull { it.effect === effect } ?: return
        panels.remove(panel)
        slots.remove(panel)
        exempt.remove(panel)
        concealed = concealed - panel
        if (width > 0 && panel.fade > 0f) {
            // Deaf and mute from here on. The component is still in the hierarchy and still takes
            // mouse events, so a press during the ~150 ms fade would otherwise reach [beginDrag],
            // where `panels.indexOf(panel)` is -1 — `dragIndex` goes negative with `dragged` set and
            // the drag state is inconsistent for as long as the button is held.
            panel.onDragStart = null
            panel.onDragMove = null
            panel.onDragEnd = null
            panel.onEdited = null
            panel.onResized = null
            ghosts += panel
            shuffling = true
            computeSlots()
            animator.start()
        } else {
            remove(panel)
        }
        revalidate()
        repaint()
    }

    /** Re-reads every pedal's name, after slots have been renumbered around a deletion. */
    fun refreshTitles() = panels.forEach { it.refreshTitle() }

    // ---------------------------------------------------------------- layout

    /** Groups the pedals into rows that fit [available] pixels, preserving chain order. */
    private fun wrap(available: Int): List<List<EffectPanel>> {
        val grouped = mutableListOf<MutableList<EffectPanel>>()
        var row = mutableListOf<EffectPanel>()
        var used = 0

        for (panel in shown()) {
            val w = panel.preferredSize.width
            val needed = if (row.isEmpty()) w else used + GAP + w
            if (row.isNotEmpty() && needed > available) {
                grouped += row
                row = mutableListOf()
                used = 0
            }
            used = if (row.isEmpty()) w else used + GAP + w
            row += panel
        }
        if (row.isNotEmpty()) grouped += row
        return if (grouped.isEmpty()) listOf(emptyList()) else grouped
    }

    private fun rowWidth(row: List<EffectPanel>): Int =
        row.sumOf { it.preferredSize.width } + GAP * (row.size - 1).coerceAtLeast(0)

    private fun pedalHeight(): Int = panels.firstOrNull()?.preferredSize?.height ?: 0

    /**
     * Recomputes where every pedal belongs, into [slots].
     *
     * Bounds are not applied here — [step] eases towards them, which is what makes the pedals slide
     * aside during a drag instead of jumping. The dragged pedal gets a slot like everything else,
     * and that slot is the gap drawn under the cursor.
     */
    private fun computeSlots() {
        rows = wrap(width.coerceAtLeast(1))
        slots.clear()

        val rowHeight = pedalHeight()
        val total = rows.size * rowHeight + (rows.size - 1).coerceAtLeast(0) * ROW_GAP
        // A lane is held at **both** ends and the rows are centred in what is left. Reserving it only
        // at the top — which is all the eye strictly needs — pushed the whole reservation above the
        // pedals and left none below, so the board had a margin along its top edge and its bottom row
        // pressed against the border.
        val lane = laneHeight()
        var y = lane + ((height - lane * 2 - total) / 2).coerceAtLeast(0)

        rows.forEachIndexed { index, row ->
            var x = (width - rowWidth(row)) / 2
            // Odd rows run right to left, so the chain snakes and each wrap is a short hop.
            val placement = if (index % 2 == 1) row.asReversed() else row
            for (panel in placement) {
                val size = panel.preferredSize
                slots[panel] = Rectangle(x, y, size.width, size.height)
                x += size.width + GAP
            }
            y += rowHeight + ROW_GAP
        }
    }

    override fun doLayout() {
        computeSlots()
        placeHeader()
        // A resize should land immediately; only a drag, a fade or a reshuffle animates. Easing a
        // window resize would make the board lag the frame edge.
        if (dragged == null && !settling && !fading && !shuffling) {
            slots.forEach { (panel, slot) -> panel.bounds = slot }
            animator.stop()
        } else {
            animator.start()
        }
    }

    /**
     * The strip across the top that no pedal is laid into: the eye's, grown to fit a LEVEL knob.
     *
     * Sized for what the right end holds even while that is hidden, so the lane is the same height
     * with one board and with two, and switching between them moves no pedal.
     */
    private fun laneHeight(): Int =
        if (headerRight.componentCount > 0) {
            maxOf(TOP_LANE, headerRight.preferredSize.height + HEADER_LANE_PAD)
        } else {
            TOP_LANE
        }

    /**
     * Pins the header to the top of what is actually on screen.
     *
     * The board is taller than its viewport whenever the chain needs more rows than fit, and a
     * control that scrolls off the top is a control you have to go looking for to undo what it did.
     * The right end is kept inside the board's own height, so on a short board below a tall one it
     * does not float up over its neighbour.
     */
    private fun placeHeader() {
        val top = visibleTop()
        val left = headerLeft.preferredSize
        headerLeft.setBounds(EYE_LEFT, top + EYE_TOP, left.width, left.height)
        val right = headerRight.preferredSize
        headerRight.setBounds(
            width - right.width - HEADER_RIGHT,
            top + ((laneHeight() - right.height) / 2).coerceAtLeast(0),
            right.width,
            right.height,
        )
        raiseHeader()
    }

    /**
     * Puts the header above every pedal in the stacking order.
     *
     * Swing paints a container's children from the highest index down, so a child added last is
     * painted first — underneath. The header was added after the pedals and sat beneath them, which
     * nobody saw while it stayed in its lane and everybody saw once the board scrolled: a pinned
     * control that the pedals slide over is a control that disappears exactly when it is needed.
     * Index 0 is painted last. A lifted pedal takes index 0 for the length of its drag and the next
     * layout, or the drop itself, puts the header back on top.
     */
    private fun raiseHeader() {
        setComponentZOrder(headerRight, 0)
        setComponentZOrder(headerLeft, 0)
    }

    /**
     * Where the viewport's top edge falls in this board's coordinates, clamped to the board.
     *
     * The board used to be the viewport's child and read `viewPosition` directly. It now sits inside
     * a stack of boards, so the position has to be translated through whatever is in between — and
     * when this board is scrolled entirely off the top, the header stays at its last row rather than
     * following the viewport out of the board.
     */
    private fun visibleTop(): Int {
        val viewport = SwingUtilities.getAncestorOfClass(JViewport::class.java, this) as? JViewport
            ?: return 0
        val view = viewport.view ?: return 0
        val top = SwingUtilities.convertPoint(view, 0, viewport.viewPosition.y, this).y
        return top.coerceIn(0, (height - laneHeight()).coerceAtLeast(0))
    }

    private val viewportListener = ChangeListener {
        placeHeader()
        repaint()
    }
    private var watchedViewport: JViewport? = null

    override fun addNotify() {
        super.addNotify()
        // Scrolling moves the viewport without laying the board out again, so the header has to be
        // told separately or it rides away with the content.
        val viewport = SwingUtilities.getAncestorOfClass(JViewport::class.java, this) as? JViewport
        if (viewport !== watchedViewport) {
            watchedViewport?.removeChangeListener(viewportListener)
            viewport?.addChangeListener(viewportListener)
            watchedViewport = viewport
        }
    }

    override fun removeNotify() {
        watchedViewport?.removeChangeListener(viewportListener)
        watchedViewport = null
        super.removeNotify()
    }

    override fun getPreferredSize(): Dimension {
        // Before the board has been sized — at pack() — wrap against a sensible desktop width rather
        // than an unbounded one, or seven pedals would open a window wider than most screens.
        val available = if (width > 0) width else UNSIZED_WIDTH
        val grouped = wrap(available)
        return Dimension(grouped.maxOf { rowWidth(it) }, preferredHeightAt(available))
    }

    /**
     * The height the board wants at [available] pixels wide.
     *
     * Separate from [getPreferredSize] because the stack that holds two boards decides their widths
     * and has to ask about heights before it has laid anything out.
     */
    fun preferredHeightAt(available: Int): Int {
        val grouped = wrap(available)
        val rows = grouped.size * pedalHeight() + (grouped.size - 1).coerceAtLeast(0) * ROW_GAP
        // The header's lane is claimed at both ends, not just the top. [computeSlots] centres the
        // rows in whatever height it is given, so asking for the lane only once left the whole
        // reservation above the pedals and none below — a gap along the top edge and the bottom
        // row pressed against the border. Claiming it twice makes the centring symmetric.
        val lane = laneHeight()
        return lane + rows + lane
    }

    /** The whole board, children included, at [alpha]. Free when nothing is fading. */
    override fun paint(g: Graphics) {
        if (alpha >= 1f) {
            super.paint(g)
            return
        }
        if (alpha <= 0f) return
        val g2 = g.create() as Graphics2D
        try {
            g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
            super.paint(g2)
        } finally {
            g2.dispose()
        }
    }

    override fun getMinimumSize(): Dimension =
        Dimension(panels.maxOfOrNull { it.preferredSize.width } ?: 0, pedalHeight())

    // ------------------------------------------------------------- scrolling

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(r: Rectangle, orientation: Int, direction: Int) = 24

    override fun getScrollableBlockIncrement(r: Rectangle, orientation: Int, direction: Int) = r.height

    /** Always take the viewport's width — that width is what the wrap is computed from. */
    override fun getScrollableTracksViewportWidth() = true

    /**
     * Fill the viewport only while the board fits in it.
     *
     * Filling means [doLayout] centres the rows in the spare height, which is what a roomy window
     * should look like. Once the rows need more height than the viewport has, the board takes its
     * own preferred height instead and the scrollbar appears.
     */
    override fun getScrollableTracksViewportHeight(): Boolean {
        val viewport = parent as? JViewport ?: return false
        return viewport.height >= preferredSize.height
    }

    // ------------------------------------------------------------- reordering

    private fun beginDrag(panel: EffectPanel, e: MouseEvent) {
        if (locked) return
        dragIndex = panels.indexOf(panel)
        dragged = panel
        settling = false
        grab = e.point
        pointer = SwingUtilities.convertPoint(panel, e.point, this)
        panel.dragging = true
        // Paint above its neighbours while it is lifted.
        setComponentZOrder(panel, 0)
        computeSlots()
        animator.start()
    }

    private fun continueDrag(panel: EffectPanel, e: MouseEvent) {
        if (locked || dragIndex < 0) return
        pointer = SwingUtilities.convertPoint(panel, e.point, this)

        val target = indexAt(pointer)
        if (target != dragIndex) {
            panels.add(target, panels.removeAt(dragIndex))
            dragIndex = target
            // Reposition rather than remove and re-add: the dragged component must stay in the
            // hierarchy or Swing's mouse capture breaks mid-drag.
            computeSlots()
        }
        animator.start()
    }

    private fun endDrag() {
        val panel = dragged ?: return
        panel.dragging = false
        dragged = null
        dragIndex = -1
        // The chain is rewired immediately; only the descent is animated. Deferring the reorder
        // until the animation finished would make a 150 ms window where the picture and the audio
        // disagree.
        onReorder?.invoke(order)
        settling = true
        // The lifted pedal was raised above everything for its drag; the header goes back on top.
        raiseHeader()
        animator.start()
    }

    /**
     * One animation frame: everything eases towards where it belongs.
     *
     * An exponential approach rather than a timed tween — each frame closes a fixed fraction of the
     * remaining distance. It needs no start time and no duration bookkeeping, and it behaves
     * correctly when the target moves mid-flight, which during a drag it does constantly.
     */
    /**
     * Each frame lays the moved pedal out again, and it has to.
     *
     * Setting a component's bounds *invalidates* it but does not schedule a layout — only
     * `revalidate` does, and that runs later, on the event queue. During an animation "later" never
     * arrives before the next frame overwrites the bounds again, so a pedal's contents stay laid out
     * for whatever size it happened to have when it was last validated. For a dragged pedal that is
     * the **lifted** size — scaled up and padded for its shadow — and the result is a pedal that
     * lands in its new slot at the right size with its switch and knobs still positioned for a box
     * ten pixels wider. `validate` does the layout now, at the size the pedal actually is; it is a
     * no-op when only x and y moved, which is every pedal except the one being dragged.
     */
    private fun step() {
        val lifted = dragged
        lift = approach(lift, if (lifted != null) 1f else 0f)

        var moving = false

        // Opacity first. A concealed pedal keeps its last bounds — it has no slot, so the loop below
        // leaves it alone — and fades where it stands while its neighbours slide into the gap it is
        // leaving. Both movements are the same easing over the same frames, which is what makes it
        // read as one gesture rather than two.
        var stillFading = false
        for (panel in panels) {
            val target = if (panel in concealed) 0f else 1f
            if (panel.fade == target) continue
            panel.fade = approach(panel.fade, target)
            if (panel.fade <= 0f) panel.isVisible = false
            stillFading = true
        }
        // Ghosts fade in the same pass and on the same easing as a concealed pedal, because they are
        // the same gesture: something leaves where it stands while the gap closes over it. Folded in
        // before `fading` is assigned, or the last frame of a departure would let [doLayout] snap.
        val leaving = ghosts.iterator()
        while (leaving.hasNext()) {
            val ghost = leaving.next()
            ghost.fade = approach(ghost.fade, 0f)
            if (ghost.fade <= 0f) {
                // Only now is it off the board for good; until here it was a child component with
                // no chain behind it.
                remove(ghost)
                leaving.remove()
            } else {
                stillFading = true
            }
        }
        fading = stillFading
        if (stillFading) moving = true

        for (panel in panels) {
            val slot = slots[panel] ?: continue
            if (panel === lifted) continue
            val now = panel.bounds
            val next = Rectangle(
                approach(now.x, slot.x),
                approach(now.y, slot.y),
                approach(now.width, slot.width),
                approach(now.height, slot.height),
            )
            if (next != now) {
                panel.bounds = next
                panel.validate()
                moving = true
            }
        }

        if (lifted != null) {
            // Follows the cursor, scaled up a touch, and clamped so it cannot be dragged out over
            // the device bar.
            val slot = slots[lifted] ?: Rectangle(0, 0, lifted.width, lifted.height)
            val scale = 1f + LIFT_SCALE * lift
            val pad = EffectPanel.LIFT_PAD
            // The component is the body plus its shadow margin, so the body — not the padded box —
            // is what sits under the cursor.
            val w = (slot.width * scale).toInt() + pad * 2
            val h = (slot.height * scale).toInt() + pad * 2
            // Rides a little above the cursor. Without it the pedal covers the gap exactly — under
            // live reflow the gap opens where the cursor is, which is where the pedal is drawn — and
            // the marker you asked for would never be visible.
            val rise = (LIFT_RISE * lift).toInt()
            lifted.setBounds(
                ((pointer.x - grab.x * scale).toInt() - pad).coerceIn(-pad, (width - w + pad).coerceAtLeast(-pad)),
                ((pointer.y - grab.y * scale).toInt() - pad - rise)
                    .coerceIn(-pad, (height - h + pad).coerceAtLeast(-pad)),
                w,
                h,
            )
            lifted.validate()
            moving = true
        } else if (settling) {
            // Nothing is held any more, so the pedal that was just dropped is easing home with the
            // rest of them; stop once it has arrived.
            moving = moving || lift > 0.01f
            if (!moving) settling = false
        }

        repaint()
        if (!moving) {
            // A reshuffle is over when nothing is travelling any more; it has no other end condition,
            // which is why it is cleared here beside the stop rather than on a timer of its own.
            shuffling = false
            animator.stop()
        }
    }

    private fun approach(from: Int, to: Int): Int {
        val delta = (to - from) * EASE
        return if (kotlin.math.abs(to - from) <= 1) to else from + if (delta > 0) maxOf(1f, delta).toInt()
        else minOf(-1f, delta).toInt()
    }

    private fun approach(from: Float, to: Float): Float =
        if (kotlin.math.abs(to - from) < 0.01f) to else from + (to - from) * EASE

    /**
     * The slot a pointer belongs in, across rows.
     *
     * Row is chosen by y, then position within that row by x — reading the row in *its* direction,
     * since odd rows run backwards.
     */
    private fun indexAt(point: Point): Int {
        if (rows.isEmpty() || panels.isEmpty()) return 0

        // Settled slots, never live bounds. The dragged pedal's live bounds sit under the cursor by
        // construction, so testing against them always matches its own slot first and the chain
        // never re-orders.
        val rowHeight = pedalHeight()
        var rowIndex = rows.lastIndex
        for (index in rows.indices) {
            val top = rows[index].firstOrNull()?.let { slots[it]?.y } ?: continue
            if (point.y < top + rowHeight + ROW_GAP / 2) {
                rowIndex = index
                break
            }
        }

        val row = rows[rowIndex]
        val leftToRight = rowIndex % 2 == 0
        var within = row.size
        for ((position, panel) in row.withIndex()) {
            val slot = slots[panel] ?: continue
            val middle = slot.x + slot.width / 2
            val isBefore = if (leftToRight) point.x < middle else point.x > middle
            if (isBefore) {
                within = position
                break
            }
        }

        val base = rows.take(rowIndex).sumOf { it.size }

        // The rows hold only what is on screen, so this position counts shown pedals — and the list
        // being reordered still contains the hidden ones. Translating through the shown pedal that
        // currently occupies the position keeps a hidden pedal anchored to its neighbour rather than
        // being stepped over silently.
        val wired = shown()
        if (wired.isEmpty()) return 0
        val position = (base + within).coerceIn(0, wired.size)
        return if (position >= wired.size) panels.lastIndex else panels.indexOf(wired[position])
    }

    private fun applyCursors() {
        val cursor = Cursor.getPredefinedCursor(if (locked) Cursor.DEFAULT_CURSOR else Cursor.MOVE_CURSOR)
        val tip = if (locked) {
            "Stop the stream to re-order the chain"
        } else {
            "Drag to move this pedal in the signal chain"
        }
        panels.forEach {
            it.cursor = cursor
            it.toolTipText = tip
        }
    }

    // ---------------------------------------------------------------- cables

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        // Cables run between what is *shown*. A hidden pedal must not leave a break in the chain —
        // the signal still goes through it, and a gap in the drawing would say otherwise.
        val wired = shown()
        if (wired.size < 2) return

        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            dragged?.let { lifted -> slots[lifted]?.let { gap -> drawGap(g2, gap) } }

            g2.stroke = BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val cable = if (locked) Theme.DISABLED else Theme.TEXT_DIM

            for (index in 0 until wired.size - 1) {
                // Slots, not live bounds: the cables should show the chain as it will be, and a
                // cable whipping around after a floating pedal is unreadable.
                val from = slots[wired[index]] ?: continue
                val to = slots[wired[index + 1]] ?: continue
                if (from.width == 0 || to.width == 0) continue
                g2.color = cable
                if (from.y == to.y) alongRow(g2, from, to) else betweenRows(g2, from, to)
            }
        } finally {
            g2.dispose()
        }
    }

    /**
     * The hole the lifted pedal will drop into: filled body plus a stronger border.
     *
     * Because the board reflows live, "where it came from" and "where it will land" are the same
     * rectangle, so both markers land on this one shape.
     */
    private fun drawGap(g2: Graphics2D, gap: Rectangle) {
        g2.color = Theme.ACCENT_TINT
        g2.fillRoundRect(gap.x, gap.y, gap.width, gap.height, 14, 14)
        g2.color = Theme.ACCENT_SOFT
        g2.stroke = BasicStroke(2f)
        g2.drawRoundRect(gap.x, gap.y, gap.width - 1, gap.height - 1, 14, 14)
    }


    /** Within a row: out of one side, into the facing side of the next, with a little slack. */
    private fun alongRow(g2: Graphics2D, from: Rectangle, to: Rectangle) {
        val rightwards = to.x > from.x
        val x1 = if (rightwards) (from.x + from.width).toDouble() else from.x.toDouble()
        val x2 = if (rightwards) to.x.toDouble() else (to.x + to.width).toDouble()
        val y = from.y + from.height / 2.0

        g2.draw(QuadCurve2D.Double(x1, y, (x1 + x2) / 2, y + SAG, x2, y))
        jack(g2, x1, y)
        jack(g2, x2, y)
        arrow(g2, (x1 + x2) / 2, y + SAG / 2, if (rightwards) 0.0 else Math.PI)
    }

    /**
     * Across a wrap: down out of the bottom of one and into the top of the next.
     *
     * Because the rows snake, these two are at the same end of the board, so this is a short hop
     * rather than a cable dragged back across everything. The cubic absorbs whatever horizontal
     * offset remains when the two pedals are different widths.
     */
    private fun betweenRows(g2: Graphics2D, from: Rectangle, to: Rectangle) {
        val x1 = from.x + from.width / 2.0
        val y1 = (from.y + from.height).toDouble()
        val x2 = to.x + to.width / 2.0
        val y2 = to.y.toDouble()
        val bow = (y2 - y1) / 2

        g2.draw(CubicCurve2D.Double(x1, y1, x1, y1 + bow, x2, y2 - bow, x2, y2))
        jack(g2, x1, y1)
        jack(g2, x2, y2)
        arrow(g2, (x1 + x2) / 2, (y1 + y2) / 2, Math.PI / 2)
    }

    private fun jack(g2: Graphics2D, x: Double, y: Double) {
        g2.fill(Ellipse2D.Double(x - JACK, y - JACK, JACK * 2, JACK * 2))
    }

    /** Flow direction, drawn as a triangle rotated to [angle] radians (0 = rightwards, y down). */
    private fun arrow(g2: Graphics2D, cx: Double, cy: Double, angle: Double) {
        val previous = g2.color
        val points = arrayOf(
            ARROW to 0.0,
            -ARROW * 0.7 to -ARROW * 0.8,
            -ARROW * 0.7 to ARROW * 0.8,
        )
        val head = Path2D.Double()
        points.forEachIndexed { index, (px, py) ->
            val rx = cx + px * cos(angle) - py * sin(angle)
            val ry = cy + px * sin(angle) + py * cos(angle)
            if (index == 0) head.moveTo(rx, ry) else head.lineTo(rx, ry)
        }
        head.closePath()
        g2.color = if (locked) Theme.DISABLED else Theme.ACCENT
        g2.fill(head)
        g2.color = previous
    }

    /**
     * A panel that paints itself, backing included, at an alpha.
     *
     * Unlike [EffectPanel.FadeRow] it has a backing, because this is the header's: the backing is
     * what keeps the controls legible over a row of pedals that has scrolled up underneath them, and
     * a header fading in over such a row should bring its backing with it.
     *
     * **It must not be opaque**, and it paints that backing itself instead. An opaque component is
     * one Swing trusts to cover every pixel, so a repaint of it alone starts at the component, into
     * a shared back buffer that is never cleared. Painted translucently, it showed what that buffer
     * last held at its top-left — the eye and the fork of a previous whole-board paint — ghosting
     * through under the LEVEL knob for a frame whenever its repaint fell in a cycle of its own. Not
     * opaque, the repaint starts at the nearest opaque ancestor and the board underneath is painted
     * first, which is what a translucent thing has to be painted over.
     */
    class FadingPanel(layout: java.awt.LayoutManager) : JPanel(layout) {

        /** 1 is normal; 0 paints nothing at all. */
        var alpha: Float = 1f
            set(value) {
                val clamped = value.coerceIn(0f, 1f)
                if (field == clamped) return
                field = clamped
                repaint()
            }

        init {
            isOpaque = false
        }

        /** The backing, drawn by hand because the panel is not opaque; see the class comment. */
        override fun paintComponent(g: Graphics) {
            g.color = background
            g.fillRect(0, 0, width, height)
        }

        override fun paint(g: Graphics) {
            if (alpha >= 1f) {
                super.paint(g)
                return
            }
            if (alpha <= 0f) return
            val g2 = g.create() as Graphics2D
            try {
                g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
                super.paint(g2)
            } finally {
                g2.dispose()
            }
        }
    }

    private companion object {
        /** Horizontal room between pedals — wide enough for a cable to look like one. */
        const val GAP = 44

        /** Vertical room between wrapped rows, sized for the hop and its arrowhead. */
        const val ROW_GAP = 46

        /** The strip along the top the eye lives in, which no pedal is laid out into. */
        const val TOP_LANE = 26

        /**
         * Where the eye's *component* goes, chosen so the eye it *draws* is inset equally from the
         * top and the left.
         *
         * They are this far apart because the component does not match its contents. It is 16 × 22
         * like every other header glyph, so all of them share a hit area — but the lens inside is
         * nearly the full width and barely a third of the height, which leaves a fraction of a pixel
         * of slack at its left and about eight at its top. Setting both offsets to the same number
         * is what put the eye against the left edge.
         *
         * The height is smaller than the drawing code looks: a quadratic Bézier reaches only **half**
         * the offset of its control point, so the lid control draws an apex only half as high.
         * Measured at 6× zoom rather than trusted — the arithmetic was wrong twice — and it has to
         * be measured again whenever the glyph's height changes, which opening the eye did.
         */
        const val EYE_LEFT = 10
        const val EYE_TOP = 4

        /** Between the header's controls, and between the right end and the board's edge. */
        const val HEADER_GAP = 6
        const val HEADER_RIGHT = 14

        /** Clear space the lane keeps above and below a control taller than the eye. */
        const val HEADER_LANE_PAD = 8

        const val SAG = 14.0
        const val JACK = 3.5
        const val ARROW = 5.0

        /** ~60 fps. */
        const val FRAME_MS = 16

        /**
         * Fraction of the remaining distance closed each frame.
         *
         * 0.35 at 16 ms lands within a pixel in about ten frames, so roughly 150 ms.
         */
        const val EASE = 0.35f

        /** How much bigger the pedal gets when picked up. Small on purpose — it should read as
         *  lifted, not as a different pedal. */
        const val LIFT_SCALE = 0.03f

        /** How far the lifted pedal rides above the cursor, so the gap stays visible under it. */
        const val LIFT_RISE = 22f

        /** Width the board wraps against before the window has been laid out, i.e. at pack(). */
        const val UNSIZED_WIDTH = 1500
    }
}
