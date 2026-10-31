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

import org.example.audio.LaneFileException
import org.example.audio.LaneFiles
import org.example.dsp.loop.Lane
import org.example.dsp.loop.LaneOrder
import org.example.dsp.loop.Looper
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.KeyboardFocusManager
import java.awt.RenderingHints
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import java.io.File
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollBar
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.text.JTextComponent
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The loop board: up to four lanes, each a row of square buttons, a volume knob and a waveform,
 * with one horizontal scrollbar under them moving all four through the minute together.
 *
 * It is a view of a [Looper] and owns none of the audio. Every button is one store into the looper
 * or one call on it, and the picture is rebuilt from the lanes thirty times a second while the
 * board is on screen — the waveform from the lane's own buffer, which the audio thread may be
 * writing at the same time; a float read while it is written is a float, and a column drawn a
 * frame early is redrawn a frame later.
 *
 * **Keys.** SIGN on a lane waits for the next key and binds it to that lane's ON toggle, so a
 * footswitch that acts as a keyboard works hands free. The dispatcher is on the keyboard focus
 * manager rather than on this component, because the board may be in the main window or popped out
 * into its own and a footswitch should not care which. Escape, Space and Enter end a recording and
 * cannot be bound. Nothing fires while a text field has focus: a bound digit must still type.
 */
class LoopBoard(
    private val looper: Looper,
    private val host: Host,
) : JPanel(BorderLayout()), Stackable {

    /** What the board needs from the window: a status line, file pickers, and somewhere to keep its keys. */
    interface Host {
        fun status(text: String, colour: Color)
        /** Whether audio is running: there is nothing to record otherwise, and REC says so. */
        fun isStreaming(): Boolean
        fun chooseLaneToLoad(): File?
        fun chooseLaneToSave(suggested: String): File?
        fun keysChanged(keys: List<Int>)
        fun popOut()
    }

    override var alpha: Float = 1f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (field == clamped) return
            field = clamped
            repaint()
        }

    /** The key bound to each lane position, as an AWT key code; zero for none. */
    private val keys = IntArray(Looper.MAX_LANES)

    /** Lane position waiting for a key after SIGN, or -1. */
    private var binding = -1

    private val rows = Array(Looper.MAX_LANES) { LaneRow(it) }
    private val lanesPanel = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }

    /** The one scrollbar: its value is the first visible pixel of the minute, shared by every waveform. */
    private val scroll = JScrollBar(JScrollBar.HORIZONTAL, 0, 100, 0, MINUTE_PIXELS).apply {
        setUI(SlimScrollBarUI())
        preferredSize = Dimension(0, 11)
        isOpaque = false
        unitIncrement = 24
        blockIncrement = 240
        model.addChangeListener { lanesPanel.repaint() }
    }

    private val popOut = EffectPanel.IconButton(EffectPanel.IconButton.Kind.POP_OUT) { host.popOut() }
        .apply { toolTipText = "Open the loop board in a window of its own" }

    private val readout = JLabel().apply {
        font = Theme.VALUE_FONT
        foreground = Theme.TEXT_DIM
    }

    private val ticker = Timer(33) { tick() }

    /**
     * Whether the time view is following the playhead: true while the playhead was in view at the
     * last tick. The view then pages when the playhead runs off its right edge and jumps back when
     * the loop wraps. A scroll the user makes, which moves the view rather than the playhead, drops
     * the follow until the playhead is in view again — scrolled back to it, or come round to it.
     */
    private var following = false

    /** The scroll value this board last set, so a change to it is known to be the user's. */
    private var lastSetScroll = -1

    /** Play and pause for the whole loop, under the lanes' button blocks and as wide as they are. */
    private val playPause = Cube("PAUSE") { togglePlay() }

    /** Whether the board is in the stack. Popped out, the pop-out icon has nothing to do and is gone. */
    var docked: Boolean = true
        set(value) {
            field = value
            popOut.isVisible = value
        }

    init {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(0, 0, 4, 0)

        val headerLeft = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0)).apply {
            isOpaque = false
            add(JLabel("LOOP").apply { font = Theme.LABEL_FONT; foreground = Theme.TEXT_DIM })
            add(readout)
        }
        val headerRight = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            isOpaque = false
            add(popOut)
        }
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(HEADER_PAD, 14, HEADER_PAD, 14)
            add(headerLeft, BorderLayout.WEST)
            add(headerRight, BorderLayout.EAST)
        }
        add(header, BorderLayout.NORTH)
        add(lanesPanel, BorderLayout.CENTER)

        // The bottom row: the transport under the lanes' button blocks, as wide as they are and
        // aligned with them, and the scrollbar under the waveforms only, so its left edge is the
        // minute's zero like the waveforms' is.
        val transport = JPanel(null).apply {
            isOpaque = false
            preferredSize = Dimension(CONTROLS_WIDTH, CUBE_H)
            playPause.setBounds(NUMBER_WIDTH + 4, 0, CUBE_W * 4 + CUBE_GAP * 3, CUBE_H)
            add(playPause)
        }
        val scrollRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            val pad = (CUBE_H - 11) / 2
            border = BorderFactory.createEmptyBorder(pad, 0, CUBE_H - 11 - pad, 0)
            add(scroll, BorderLayout.CENTER)
        }
        add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                border = BorderFactory.createEmptyBorder(BOTTOM_GAP, LANE_INSET, 0, LANE_INSET)
                add(transport, BorderLayout.WEST)
                add(scrollRow, BorderLayout.CENTER)
            },
            BorderLayout.SOUTH,
        )
        syncPlay()
        rebuildRows()
        installKeys()
    }

    // ------------------------------------------------------------------- state

    /** Puts saved bindings back. Zero, or a code for a reserved key, is no binding. */
    fun setKeys(saved: List<Int>) {
        for (i in keys.indices) keys[i] = saved.getOrNull(i)?.takeIf { it !in RESERVED_KEYS } ?: 0
        rows.forEach { it.syncKey() }
    }

    override fun preferredHeightAt(available: Int): Int =
        HEADER_PAD * 2 + 22 + looper.laneCount * (LANE_HEIGHT + LANE_GAP) + BOTTOM_GAP + CUBE_H + 4

    /**
     * The transport. Paused, every lane fades to silence and the playhead holds; play resumes from
     * there. Pausing ends a recording where it is, since there is nothing to record into a held
     * loop, and REC while paused starts the transport again.
     */
    private fun togglePlay() {
        if (looper.playing) {
            stopAllRecording()
            looper.playing = false
            host.status("Loop paused. Play resumes from here.", Theme.TEXT_DIM)
        } else {
            looper.playing = true
            host.status("Loop playing.", Theme.GOOD)
        }
        syncPlay()
    }

    private fun syncPlay() {
        val playing = looper.playing
        playPause.label = if (playing) "❚❚  PAUSE" else "▶  PLAY"
        playPause.lit = playing
        playPause.toolTipText = if (playing) "Pause the loop where it is" else "Play the loop from where it paused"
    }

    override fun getPreferredSize(): Dimension = Dimension(960, preferredHeightAt(width))

    private fun rebuildRows() {
        lanesPanel.removeAll()
        for (i in 0 until looper.laneCount) {
            if (i > 0) lanesPanel.add(Box.createVerticalStrut(LANE_GAP))
            lanesPanel.add(rows[i])
            rows[i].invalidateWave()
        }
        rows.forEach { it.syncButtons() }
        revalidate()
        repaint()
        // The stack sizes from preferredHeightAt; a popped-out window grows or shrinks to the new
        // lane count but keeps whatever width it was given.
        parent?.revalidate()
        (SwingUtilities.getWindowAncestor(this) as? LoopWindow)?.let { window ->
            window.setSize(window.width, window.preferredSize.height)
            window.validate()
        }
    }

    /** Ends every recording and arm, from the stop keys or the panel closing. */
    fun stopAllRecording() {
        for (lane in looper.lanes) looper.stopRecording(lane)
    }

    private fun tick() {
        val rate = looper.sampleRate
        val length = looper.loopLength
        readout.text = if (length == 0) {
            val first = looper.lanes.firstOrNull { it.recording }
            if (first != null) {
                // The first recording is the loop being measured, so the count runs as it goes.
                String.format(
                    java.util.Locale.ROOT,
                    "recording lane %d — %.1f s, up to %d s; stop sets the loop",
                    first.index + 1,
                    first.recordedFrames / rate,
                    Looper.MAX_SECONDS,
                )
            } else if (looper.isRecording) {
                "recording the first lane — it sets the loop"
            } else {
                "no loop yet — REC a lane to set one"
            }
        } else {
            val at = looper.position / rate
            val of = length / rate
            val where = String.format(java.util.Locale.ROOT, "%.1f s of %.1f s", at, of)
            if (looper.playing) where else "paused at $where"
        }
        if (playPause.lit != looper.playing) syncPlay()
        followPlayhead(length, rate)
        for (i in 0 until looper.laneCount) rows[i].refresh()
    }

    /**
     * Keeps the playhead in the time view while it is there to be kept.
     *
     * Two things can take the playhead out of view: the playhead moving, or the view moving. Only
     * the first is followed. A scroll the user made shows as a scroll value this board did not set,
     * and that drops the follow — the user is looking somewhere else on purpose — until the
     * playhead is in view again, by their scrolling back or by its coming round. Following, the
     * view pages when the playhead runs off the right edge, so the picture is still under the
     * pointer between pages, and jumps back when the loop wraps.
     */
    private fun followPlayhead(length: Int, rate: Float) {
        val extent = scroll.model.extent
        // No loop yet is still something to follow while the first lane records: the playhead is
        // the front of that recording, running from the top at the same speed.
        if ((length == 0 && !looper.isRecording) || extent <= 0) {
            following = false
            lastSetScroll = scroll.value
            return
        }
        val px = (looper.position / rate * PX_PER_SECOND).roundToInt()
        var value = scroll.value
        val userScrolled = lastSetScroll >= 0 && value != lastSetScroll
        val inView = px >= value && px < value + extent
        if (userScrolled) {
            following = inView
        } else if (following && !inView) {
            value = (px - FOLLOW_LEAD).coerceIn(0, max(0, MINUTE_PIXELS - extent))
            scroll.value = value
        } else {
            following = inView
        }
        lastSetScroll = scroll.value
    }

    override fun addNotify() {
        super.addNotify()
        ticker.start()
    }

    override fun removeNotify() {
        ticker.stop()
        super.removeNotify()
    }

    /** The whole board, children included, at [alpha]; see [ChainBoard.paint]. */
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

    // -------------------------------------------------------------------- keys

    private fun installKeys() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher { e -> dispatch(e) }
    }

    /** True when the event was ours and must go no further. */
    private fun dispatch(e: KeyEvent): Boolean {
        if (e.id != KeyEvent.KEY_PRESSED) return false
        val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        if (manager.focusedWindow == null) return false
        val owner = manager.focusOwner
        if (owner is JTextComponent && owner.isEditable) return false
        if (owner is javax.swing.JComboBox<*> && owner.isPopupVisible) return false
        val code = e.keyCode

        if (binding >= 0) {
            val lane = binding
            binding = -1
            when {
                // Escape while waiting clears the lane's key: KEY then Escape is how a binding is
                // undone, since a lane with no key has nothing else to press to say so.
                code == KeyEvent.VK_ESCAPE -> {
                    val had = keys[lane] != 0
                    keys[lane] = 0
                    host.keysChanged(keys.toList())
                    host.status(
                        if (had) "Lane ${lane + 1} has no key now." else "No key bound to lane ${lane + 1}.",
                        Theme.TEXT_DIM,
                    )
                }
                code in RESERVED_KEYS || code == KeyEvent.VK_UNDEFINED ->
                    host.status("${KeyEvent.getKeyText(code)} is for stopping a recording and cannot be bound.", Theme.BAD)
                else -> {
                    for (i in keys.indices) if (keys[i] == code) keys[i] = 0
                    keys[lane] = code
                    host.keysChanged(keys.toList())
                    host.status("${KeyEvent.getKeyText(code)} now toggles lane ${lane + 1}.", Theme.GOOD)
                }
            }
            rows.forEach { it.syncKey() }
            return true
        }

        if (code in RESERVED_KEYS && looper.isRecording) {
            stopAllRecording()
            return true
        }

        for (i in 0 until looper.laneCount) {
            if (keys[i] != 0 && keys[i] == code) {
                val lane = looper.lanes[i]
                lane.active = !lane.active
                rows[i].syncButtons()
                return true
            }
        }
        return false
    }

    // -------------------------------------------------------------------- lanes

    /** One lane: its number, its block of buttons, its knob, its waveform. */
    private inner class LaneRow(private val index: Int) : JPanel(BorderLayout()) {

        private val lane: Lane get() = looper.lanes[index]

        private val remove = Cube("✕", danger = true) { removeLane() }
        private val addLane = Cube("+") { addLane() }
        private val save = Cube("SAVE") { saveLane() }
        private val load = Cube("LOAD") { loadLane() }
        private val record = Cube("REC") { toggleRecord() }
        private val order = Cube("AFTER") { toggleOrder() }
        private val key = Cube("KEY") { beginBinding() }
        private val active = Cube("ON") { toggleActive() }

        private val knob = Knob(lane.level, compact = true).apply {
            toolTipText = "Lane ${index + 1} level — drag to adjust, shift for fine, double-click to reset"
        }
        private val knobValue = JLabel().apply {
            font = Theme.VALUE_FONT
            foreground = Theme.TEXT
            horizontalAlignment = JLabel.CENTER
        }

        private val wave = Waveform()

        init {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            preferredSize = Dimension(0, LANE_HEIGHT)
            minimumSize = Dimension(0, LANE_HEIGHT)
            maximumSize = Dimension(Int.MAX_VALUE, LANE_HEIGHT)
            border = BorderFactory.createEmptyBorder(0, LANE_INSET, 0, LANE_INSET)

            val number = JLabel("${index + 1}").apply {
                font = Theme.TITLE_FONT
                foreground = Theme.TEXT_DIM
                horizontalAlignment = JLabel.CENTER
                preferredSize = Dimension(NUMBER_WIDTH, LANE_HEIGHT)
            }
            val cubes = JPanel(java.awt.GridLayout(2, 4, CUBE_GAP, CUBE_GAP)).apply {
                isOpaque = false
                add(remove); add(addLane); add(save); add(load)
                add(record); add(order); add(key); add(active)
                preferredSize = Dimension(CUBE_W * 4 + CUBE_GAP * 3, CUBE_H * 2 + CUBE_GAP)
            }
            val cubeHolder = JPanel(java.awt.GridBagLayout()).apply {
                isOpaque = false
                add(cubes)
                preferredSize = Dimension(CUBE_W * 4 + CUBE_GAP * 3 + 8, LANE_HEIGHT)
            }
            val knobColumn = JPanel(BorderLayout()).apply {
                isOpaque = false
                preferredSize = Dimension(KNOB_WIDTH, LANE_HEIGHT)
                add(JPanel(java.awt.GridBagLayout()).apply { isOpaque = false; add(knob) }, BorderLayout.CENTER)
                add(knobValue, BorderLayout.SOUTH)
            }
            val controls = JPanel(BorderLayout()).apply {
                isOpaque = false
                preferredSize = Dimension(CONTROLS_WIDTH, LANE_HEIGHT)
                add(number, BorderLayout.WEST)
                add(cubeHolder, BorderLayout.CENTER)
                add(knobColumn, BorderLayout.EAST)
            }
            add(controls, BorderLayout.WEST)
            add(wave, BorderLayout.CENTER)

            knob.onChange = { syncKnob() }
            knob.onClick = {
                lane.active = !lane.active
                syncButtons()
            }
            syncKnob()
            syncButtons()
            syncKey()
        }

        fun invalidateWave() = wave.invalidateCache()

        /** Thirty times a second: the parts that follow the audio thread. */
        fun refresh() {
            val recording = lane.recording
            val armed = lane.armed
            if (record.red != recording || record.outlined != armed) {
                record.red = recording
                record.outlined = armed
                record.label = if (recording) "STOP" else "REC"
                record.repaint()
            }
            // ✕ means clear while there is something to clear and remove once there is not, so it
            // follows the lane's contents as they come and go.
            if (seenEmpty != lane.isEmpty) syncButtons()
            wave.refresh()
        }

        private var seenEmpty = true

        fun syncKnob() {
            knobValue.text = lane.level.formatted()
        }

        fun syncButtons() {
            val count = looper.laneCount
            // Both always there, so every lane's block is the same shape; the one that cannot be
            // used here is dimmed rather than missing. ✕ clears a lane that holds something and
            // removes one that is empty — except the first lane, which stays; a lane is added only
            // at the bottom while there is room.
            val empty = lane.isEmpty
            seenEmpty = empty
            remove.dimmed = index == 0 && empty
            addLane.dimmed = !(index == count - 1 && count < Looper.MAX_LANES)
            order.label = if (lane.order == LaneOrder.AFTER) "AFTER" else "THRU"
            order.toolTipText = if (lane.order == LaneOrder.AFTER) {
                "Plays after the master, under the live guitar — click for through the boards"
            } else {
                "Plays into the start of the chain, through the pedals — click for after the master"
            }
            active.lit = lane.active
            active.toolTipText = if (lane.active) "Lane ${index + 1} is heard — click to silence it" else "Lane ${index + 1} is silent — click to hear it"
            remove.toolTipText = when {
                !empty -> "Clear lane ${index + 1}"
                index == 0 -> "Lane 1 is empty and stays"
                else -> "Remove lane ${index + 1}"
            }
            addLane.toolTipText = "Add a lane"
            save.toolTipText = "Save this lane as a WAV file"
            load.toolTipText = "Load a sound file into this lane — its first minute"
            record.toolTipText = "Record this lane from the top of the loop; press again, Esc, Space or Enter to stop"
            repaint()
        }

        fun syncKey() {
            val waiting = binding == index
            key.outlined = waiting
            key.label = when {
                waiting -> "…"
                keys[index] != 0 -> KeyEvent.getKeyText(keys[index]).uppercase().take(5)
                else -> "KEY"
            }
            key.toolTipText = if (keys[index] != 0) {
                "${KeyEvent.getKeyText(keys[index])} toggles this lane — click to bind another key"
            } else {
                "Click, then press the key or footswitch that should toggle this lane"
            }
            key.repaint()
        }

        /** ✕: clears a lane that holds something; removes one that is already empty. */
        private fun removeLane() {
            if (lane.recording || lane.armed) {
                host.status("Stop the recording first.", Theme.BAD)
                return
            }
            if (!lane.isEmpty) {
                looper.clear(lane)
                wave.invalidateCache()
                syncButtons()
                host.status(
                    if (looper.loopLength == 0) "Lane ${index + 1} cleared. The loop is free again." else "Lane ${index + 1} cleared.",
                    Theme.TEXT_DIM,
                )
                return
            }
            if (looper.isRecording) {
                host.status("Stop the recording before removing a lane.", Theme.BAD)
                return
            }
            if (!looper.removeLane(index)) return
            rebuildRows()
            host.status("Lane ${index + 1} removed.", Theme.TEXT_DIM)
        }

        private fun addLane() {
            looper.addLane() ?: return
            rebuildRows()
        }

        private fun saveLane() {
            if (lane.isEmpty) {
                host.status("Lane ${index + 1} is empty — nothing to save.", Theme.BAD)
                return
            }
            if (lane.recording) {
                host.status("Stop the recording first.", Theme.BAD)
                return
            }
            val file = host.chooseLaneToSave("lane ${index + 1}.wav") ?: return
            val rate = looper.sampleRate.roundToInt()
            try {
                LaneFiles.save(file, lane.buffer, lane.length, rate)
                host.status("Saved lane ${index + 1} to \"${file.name}\".", Theme.GOOD)
            } catch (e: LaneFileException) {
                host.status("\"${file.name}\" ${e.message}", Theme.BAD)
            }
        }

        private fun loadLane() {
            if (lane.recording || lane.armed) {
                host.status("Stop the recording first.", Theme.BAD)
                return
            }
            val file = host.chooseLaneToLoad() ?: return
            val loaded = try {
                LaneFiles.load(file, looper.sampleRate)
            } catch (e: LaneFileException) {
                host.status("\"${file.name}\" ${e.message}", Theme.BAD)
                return
            }
            looper.load(lane, loaded.samples, loaded.count, loaded.name)
            wave.invalidateCache()
            host.status(
                if (loaded.truncated) {
                    "Loaded the first ${Looper.MAX_SECONDS} s of \"${file.name}\" into lane ${index + 1}."
                } else {
                    "Loaded \"${file.name}\" into lane ${index + 1}."
                },
                Theme.GOOD,
            )
        }

        private fun toggleRecord() {
            if (lane.recording || lane.armed) {
                looper.stopRecording(lane)
                return
            }
            if (looper.isRecording) {
                host.status("Another lane is recording — one at a time.", Theme.BAD)
                return
            }
            if (!looper.enabled) {
                host.status("The loop board is off.", Theme.BAD)
                return
            }
            if (!host.isStreaming()) {
                host.status("Start the stream first — there is nothing to record until audio is running.", Theme.BAD)
                return
            }
            // A recording needs the transport running; REC while paused is a request to go.
            if (!looper.playing) {
                looper.playing = true
                syncPlay()
            }
            looper.arm(lane)
            wave.invalidateCache()
            host.status(
                if (looper.loopLength == 0) {
                    "Recording lane ${index + 1}. Press REC again, Esc, Space or Enter to stop; that sets the loop."
                } else {
                    "Lane ${index + 1} records from the top of the loop and stops when it comes round."
                },
                Theme.GOOD,
            )
        }

        private fun toggleOrder() {
            lane.order = if (lane.order == LaneOrder.AFTER) LaneOrder.THROUGH else LaneOrder.AFTER
            syncButtons()
        }

        private fun beginBinding() {
            binding = if (binding == index) -1 else index
            rows.forEach { it.syncKey() }
            if (binding == index) {
                host.status("Press the key or footswitch for lane ${index + 1}. Esc clears it.", Theme.GOOD)
            }
        }

        private fun toggleActive() {
            lane.active = !lane.active
            syncButtons()
        }

        /**
         * The lane's audio, a column of min and max per pixel, with the playhead over it.
         *
         * The peaks are cached per column and extended as a recording grows, so a lane in progress
         * costs the columns it gained since the last frame rather than a scan of the whole buffer.
         */
        private inner class Waveform : JComponent() {

            private val peaks = FloatArray(MINUTE_PIXELS * 2)
            private var cachedColumns = 0

            /** The loudest sample seen in the cached columns; the picture is scaled so it reaches the top. */
            private var loudest = 0f
            private var seenLength = -1
            private var seenSource: String? = null
            private var seenRecording = false

            init {
                isOpaque = false
                cursor = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
                toolTipText = "Click to play from here"
                addMouseListener(object : MouseAdapter() {
                    override fun mousePressed(e: MouseEvent) {
                        if (!SwingUtilities.isLeftMouseButton(e)) return
                        seekTo(e.x)
                    }
                })
                addMouseMotionListener(object : MouseAdapter() {
                    // Dragging scrubs: the playhead follows the pointer until the button goes up.
                    override fun mouseDragged(e: MouseEvent) {
                        if (SwingUtilities.isLeftMouseButton(e)) seekTo(e.x)
                    }
                })
                addMouseWheelListener { e ->
                    // The wheel over a waveform scrolls time, since there is nothing else to scroll
                    // here — unless the notch belongs to a scroll of the stack that brought the
                    // waveform under the pointer, which carries on. See WheelGesture.
                    if (!WheelGesture.take(this@LoopBoard)) {
                        WheelGesture.passUp(this, e)
                        return@addMouseWheelListener
                    }
                    scroll.value = scroll.value + e.wheelRotation * scroll.unitIncrement * 3
                }
                addComponentListener(object : java.awt.event.ComponentAdapter() {
                    override fun componentResized(e: java.awt.event.ComponentEvent) = syncExtent()
                })
            }

            fun invalidateCache() {
                cachedColumns = 0
                loudest = 0f
                seenLength = -1
                repaint()
            }

            /** Puts the playhead at the time under [x] of this waveform; every lane shares it. */
            private fun seekTo(x: Int) {
                if (looper.loopLength == 0) {
                    host.status("No loop yet — REC a lane to set one.", Theme.BAD)
                    return
                }
                if (looper.isRecording) {
                    host.status("Stop the recording before moving the playhead.", Theme.BAD)
                    return
                }
                val frame = ((x + scroll.value).toDouble() / PX_PER_SECOND * looper.sampleRate).roundToInt()
                looper.seek(frame)
                lanesPanel.repaint()
            }

            fun refresh() {
                val l = lane
                val recording = l.recording
                val length = if (recording) l.recordedFrames else l.length
                if (l.source !== seenSource || length < seenLength || recording != seenRecording) {
                    cachedColumns = 0
                    loudest = 0f
                }
                seenSource = l.source
                seenLength = length
                seenRecording = recording
                repaint()
            }

            private fun syncExtent() {
                val extent = width.coerceAtLeast(1)
                scroll.model.extent = min(extent, MINUTE_PIXELS)
                scroll.value = scroll.value.coerceIn(0, max(0, MINUTE_PIXELS - extent))
            }

            /** Brings the peak cache up to the lane's current length. */
            private fun extendCache(length: Int, rate: Float) {
                val perColumn = rate / PX_PER_SECOND
                val buffer = lane.buffer
                val complete = (length / perColumn).toInt().coerceAtMost(MINUTE_PIXELS)
                var column = cachedColumns
                while (column < complete) {
                    val from = (column * perColumn).toInt()
                    val to = ((column + 1) * perColumn).toInt().coerceAtMost(min(length, buffer.size))
                    var lo = 0f
                    var hi = 0f
                    for (i in from until to) {
                        val v = buffer[i]
                        if (v < lo) lo = v
                        if (v > hi) hi = v
                    }
                    peaks[column * 2] = lo
                    peaks[column * 2 + 1] = hi
                    if (hi > loudest) loudest = hi
                    if (-lo > loudest) loudest = -lo
                    column++
                }
                cachedColumns = column
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    val w = width
                    val h = height
                    g2.color = Theme.PANEL
                    g2.fill(RoundRectangle2D.Float(0f, 0f, w.toFloat(), h.toFloat(), 8f, 8f))

                    val l = lane
                    val rate = looper.sampleRate
                    val offset = scroll.value
                    val loopPx = if (looper.loopLength > 0) (looper.loopLength / rate * PX_PER_SECOND).roundToInt() else 0

                    // The part of the minute beyond the loop is never played: shaded down.
                    if (loopPx > 0) {
                        val x = loopPx - offset
                        if (x < w) {
                            g2.color = Color(0, 0, 0, 70)
                            g2.fillRect(max(x, 0), 0, w - max(x, 0), h)
                            g2.color = Theme.TRACK
                            if (x >= 0) g2.drawLine(x, 0, x, h)
                        }
                    }
                    // Second marks along the bottom, every five seconds a longer one.
                    g2.color = Theme.TRACK
                    val firstSecond = offset / PX_PER_SECOND
                    val lastSecond = (offset + w) / PX_PER_SECOND + 1
                    for (s in firstSecond..min(lastSecond, Looper.MAX_SECONDS)) {
                        val x = s * PX_PER_SECOND - offset
                        val tall = if (s % 5 == 0) 6 else 3
                        g2.drawLine(x, h - tall, x, h)
                    }
                    g2.color = Theme.TRACK
                    g2.drawLine(0, h / 2, w, h / 2)

                    val recording = l.recording
                    val length = if (recording) l.recordedFrames else l.length
                    if (length > 0) {
                        extendCache(length, rate)
                        val dimmed = !l.active && !recording
                        g2.color = when {
                            recording -> Theme.BAD
                            dimmed -> Theme.DISABLED
                            else -> Theme.TEXT_DIM
                        }
                        val mid = h / 2f
                        // Scaled to the lane's own loudest sample, so a quiet take fills the lane
                        // as a loud one does and the shape is what is seen, not the level. A floor
                        // keeps near-silence from being blown up into noise.
                        val scale = (h / 2f - 6f) / max(loudest, QUIET_FLOOR)
                        val firstColumn = offset.coerceAtLeast(0)
                        val lastColumn = min(offset + w, cachedColumns)
                        for (column in firstColumn until lastColumn) {
                            val lo = peaks[column * 2] * scale
                            val hi = peaks[column * 2 + 1] * scale
                            val x = column - offset
                            val top = mid - max(hi, 0.5f)
                            val bottom = mid - min(lo, -0.5f)
                            g2.drawLine(x, top.roundToInt(), x, bottom.roundToInt())
                        }
                    }

                    // The playhead, over everything, when there is a loop to be in — or a first
                    // recording under way, whose front it is.
                    if (looper.enabled && (looper.loopLength > 0 || looper.isRecording)) {
                        val x = (looper.position / rate * PX_PER_SECOND).roundToInt() - offset
                        if (x in 0 until w) {
                            g2.color = Theme.ACCENT
                            g2.stroke = BasicStroke(1.5f)
                            g2.drawLine(x, 2, x, h - 2)
                        }
                    }

                    // What the lane holds, in the corner, so an empty lane says so.
                    g2.font = Theme.LABEL_FONT
                    g2.color = Theme.TEXT_DIM
                    val caption = when {
                        recording -> "RECORDING"
                        l.isEmpty -> "EMPTY"
                        else -> (l.source ?: "").uppercase()
                    }
                    g2.drawString(caption, 8, 14)
                } finally {
                    g2.dispose()
                }
            }
        }
    }

    /**
     * A small square-cornered button with a word on it, the lane's "cubes".
     *
     * Three looks beyond rest: [lit] in the accent for a toggle that is on, [red] for a recording in
     * progress, and [outlined] for something waiting — an arm, or a key binding. Hover warms the
     * fill as every other button here does.
     */
    private class Cube(
        label: String,
        private val danger: Boolean = false,
        private val onClick: () -> Unit,
    ) : JComponent() {

        var label: String = label
            set(value) {
                if (field == value) return
                field = value
                repaint()
            }

        var lit = false
            set(value) {
                if (field == value) return
                field = value
                repaint()
            }

        var red = false
        var outlined = false
        private var hover = false

        /** Present but not usable here: drawn faint, and a click on it does nothing. */
        var dimmed = false
            set(value) {
                if (field == value) return
                field = value
                cursor = Cursor.getPredefinedCursor(if (value) Cursor.DEFAULT_CURSOR else Cursor.HAND_CURSOR)
                repaint()
            }

        init {
            preferredSize = Dimension(CUBE_W, CUBE_H)
            minimumSize = preferredSize
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
                override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
            })
            Clicks.onRelease(this, enabled = { isEnabled && !dimmed }) { onClick() }
        }

        // Hover is learnt from enter and exit alone, and a cube that leaves the tree under the
        // pointer — the ✕ whose click removed its own lane — never gets the exit. The row is kept
        // and comes back with the next +, so the stale hover has to be dropped here.
        override fun removeNotify() {
            hover = false
            super.removeNotify()
        }

        override fun setVisible(visible: Boolean) {
            if (!visible) hover = false
            super.setVisible(visible)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                if (dimmed) g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, DIMMED_ALPHA)
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                val shape = RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, 5f, 5f)
                g2.color = when {
                    red -> Theme.BAD
                    lit -> Theme.ACCENT
                    hover && !dimmed -> if (danger) Color(0x5A, 0x36, 0x36) else Theme.ACCENT_TINT
                    else -> Theme.PANEL_RAISED
                }
                g2.fill(shape)
                if (outlined) {
                    g2.color = Theme.BAD
                    g2.stroke = BasicStroke(1.5f)
                    g2.draw(shape)
                }
                g2.font = Theme.LABEL_FONT
                g2.color = when {
                    red || lit -> Theme.BACKGROUND
                    danger && hover && !dimmed -> Theme.BAD
                    isEnabled -> Theme.TEXT
                    else -> Theme.DISABLED
                }
                val metrics = g2.fontMetrics
                val tx = (width - metrics.stringWidth(label)) / 2
                val ty = (height - metrics.height) / 2 + metrics.ascent
                g2.drawString(label, tx, ty)
            } finally {
                g2.dispose()
            }
        }
    }

    companion object {
        /**
         * How a minute is drawn: ninety pixels a second, so the whole of it is 5400 pixels wide.
         * Thirty was too coarse — a column held nearly fifteen hundred samples and a guitar's picking
         * averaged into a smooth band; at ninety the attacks and the gaps between them show.
         */
        const val PX_PER_SECOND = 90

        /** The quietest "loudest sample" a lane is scaled to; below it the picture is left small. */
        const val QUIET_FLOOR = 0.05f

        /** Where the playhead lands in the view after a page: a little in from the left edge. */
        const val FOLLOW_LEAD = 48
        const val MINUTE_PIXELS = PX_PER_SECOND * Looper.MAX_SECONDS

        const val LANE_HEIGHT = 84
        const val LANE_GAP = 6
        const val LANE_INSET = 10
        const val HEADER_PAD = 6

        const val CUBE_W = 46
        const val CUBE_H = 26
        const val CUBE_GAP = 4

        /** Between the last lane and the bottom row. */
        const val BOTTOM_GAP = 6

        /** How faint a cube is drawn when it cannot be used on its lane. */
        const val DIMMED_ALPHA = 0.3f
        const val NUMBER_WIDTH = 22
        const val KNOB_WIDTH = 56

        /** Number, four cubes with their gaps and a little air, the knob column, and a gap before the wave. */
        const val CONTROLS_WIDTH = NUMBER_WIDTH + CUBE_W * 4 + CUBE_GAP * 3 + 8 + KNOB_WIDTH + 8

        /** The keys that stop a recording, and so cannot be bound to a lane. */
        val RESERVED_KEYS = setOf(KeyEvent.VK_ESCAPE, KeyEvent.VK_SPACE, KeyEvent.VK_ENTER)
    }
}
