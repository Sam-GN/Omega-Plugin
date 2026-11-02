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

import java.awt.Frame
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent

/**
 * Where a window was: its normal bounds and whether it was maximised. The main window and the
 * loop board's pop-out both keep one in the settings and come back to it on the next run — on the
 * same monitor, at the same size, maximised if they were — when that place still exists.
 *
 * "Still exists" is judged by the title bar, not the rectangle: a window whose only visible part
 * is under the taskbar or a sliver on the far edge cannot be grabbed, so [resolve] asks whether a
 * hand's width of the bar lies inside some monitor's work area. A monitor that has been unplugged
 * fails that, and the caller centres the window as it always did. A monitor is never named; AWT
 * puts every screen in one coordinate space, so a position *is* a monitor.
 *
 * The maximised state is stored beside the normal bounds rather than instead of them, which is what
 * lets a window closed maximised come back maximised and then restore to where it was. A [Tracker]
 * keeps the normal bounds, since a maximised frame's own bounds are the screen's.
 */
data class WindowPlacement(val bounds: Rectangle, val maximised: Boolean) {

    /** `x,y,width,height`, with `,max` on the end when maximised. The settings keep this. */
    fun text(): String = "${bounds.x},${bounds.y},${bounds.width},${bounds.height}" + if (maximised) ",max" else ""

    /** What [resolve] decided: the bounds to set, the work area of the monitor they are on, and whether to maximise. */
    data class Resolved(val bounds: Rectangle, val workArea: Rectangle, val maximised: Boolean) {
        /** The centre of the window as it will appear — the work area's when maximised. */
        val centre: Point
            get() {
                val r = if (maximised) workArea else bounds
                return Point(r.x + r.width / 2, r.y + r.height / 2)
            }
    }

    /**
     * Keeps the normal bounds of [frame] up to date. Bounds reported while the frame is maximised
     * or iconified are the screen's or Windows' parking place, not the user's, and are skipped.
     */
    class Tracker(private val frame: Frame) {
        private var normal: Rectangle = frame.bounds

        /** The normal bounds, set directly: for the bounds [apply] gives a frame it is about to maximise. */
        fun remember(bounds: Rectangle) {
            normal = Rectangle(bounds)
        }

        init {
            frame.addComponentListener(object : ComponentAdapter() {
                override fun componentMoved(e: ComponentEvent) = note()
                override fun componentResized(e: ComponentEvent) = note()
            })
        }

        private fun note() {
            if (frame.extendedState and (Frame.MAXIMIZED_BOTH or Frame.ICONIFIED) != 0) return
            if (frame.width <= 0 || frame.height <= 0) return
            normal = frame.bounds
        }

        /** The placement as it stands: the frame's own bounds while they are the user's, the kept ones otherwise. */
        fun current(): WindowPlacement {
            val state = frame.extendedState
            val parked = state and (Frame.MAXIMIZED_BOTH or Frame.ICONIFIED) != 0
            return WindowPlacement(Rectangle(if (parked) normal else frame.bounds), state and Frame.MAXIMIZED_BOTH != 0)
        }
    }

    companion object {
        /** How much of the title bar must be on a monitor for the place to count: a hand's width by most of the bar. */
        const val GRIP_WIDTH = 100
        const val GRIP_HEIGHT = 24

        /** The title bar's height, which is the strip the grip is looked for in. */
        private const val BAR_HEIGHT = 34

        /** Below this a saved size is treated as nonsense rather than restored. */
        private const val MIN_WIDTH = 200
        private const val MIN_HEIGHT = 100

        /** Reads [text] back; null for anything that is not a placement. The old four-part form still parses. */
        fun parse(text: String?): WindowPlacement? {
            val parts = text?.split(',')?.map { it.trim() } ?: return null
            if (parts.size != 4 && parts.size != 5) return null
            val numbers = parts.take(4).map { it.toIntOrNull() ?: return null }
            if (numbers[2] < MIN_WIDTH || numbers[3] < MIN_HEIGHT) return null
            val maximised = parts.size == 5 && parts[4] == "max"
            if (parts.size == 5 && !maximised) return null
            return WindowPlacement(Rectangle(numbers[0], numbers[1], numbers[2], numbers[3]), maximised)
        }

        /**
         * Decides whether [placement] can be used on the monitors described by [workAreas] — the
         * screens as they are now, minus what the desktop reserves — and what to set if so. Null
         * means centre the window instead. The size is cut down to the work area it lands on, for
         * a monitor that has got smaller since; the position is the user's and is left alone.
         */
        fun resolve(placement: WindowPlacement?, workAreas: List<Rectangle> = currentWorkAreas()): Resolved? {
            if (placement == null) return null
            val bar = Rectangle(placement.bounds.x, placement.bounds.y, placement.bounds.width, BAR_HEIGHT)
            val area = workAreas
                .map { it to it.intersection(bar) }
                .filter { (_, grip) -> grip.width >= GRIP_WIDTH && grip.height >= GRIP_HEIGHT }
                .maxByOrNull { (_, grip) -> grip.width * grip.height }
                ?.first ?: return null
            val bounds = Rectangle(
                placement.bounds.x,
                placement.bounds.y,
                minOf(placement.bounds.width, area.width),
                minOf(placement.bounds.height, area.height),
            )
            return Resolved(bounds, area, placement.maximised)
        }

        /**
         * Puts [frame] where [placement] says, if that place still exists, and says whether it did.
         * Maximising goes through `maximizedBounds` set to the monitor's work area, the same step
         * the title bar takes, because an undecorated frame maximised without it covers the taskbar.
         * The frame's [tracker] is told the normal bounds directly: the move event for them arrives
         * after the frame is already maximised, when the tracker would rightly ignore it, and a
         * window opened maximised would otherwise have no normal bounds to go back to.
         */
        fun apply(frame: Frame, placement: WindowPlacement?, tracker: Tracker? = null): Boolean {
            val resolved = resolve(placement) ?: return false
            frame.bounds = resolved.bounds
            tracker?.remember(resolved.bounds)
            if (resolved.maximised) {
                frame.maximizedBounds = resolved.workArea
                frame.extendedState = Frame.MAXIMIZED_BOTH
            }
            return true
        }

        /** Every monitor's work area, in the shared coordinate space. */
        fun currentWorkAreas(): List<Rectangle> =
            GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { workArea(it.defaultConfiguration) }

        /** [config]'s screen minus whatever the desktop reserves, i.e. the taskbar. */
        fun workArea(config: GraphicsConfiguration): Rectangle {
            val bounds = config.bounds
            val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
            return Rectangle(
                bounds.x + insets.left,
                bounds.y + insets.top,
                bounds.width - insets.left - insets.right,
                bounds.height - insets.top - insets.bottom,
            )
        }
    }
}
