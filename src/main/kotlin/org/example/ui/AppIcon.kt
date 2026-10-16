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

import java.awt.Image
import java.awt.Taskbar
import javax.imageio.ImageIO

/**
 * The application icon, pre-rendered at several sizes.
 *
 * Several rather than one because the window decoration, the alt-tab switcher and the taskbar all
 * ask for different sizes, and handing them a single large image makes each downscale it on its own
 * — usually badly. Given a set, the platform picks the nearest and the small sizes stay sharp.
 *
 * Two sources, both in the project root: `omega_icon_v6.svg` for 48 px and up, and
 * `omega_icon_v6_small.svg` for 32 px and down. The small variant drops the frets, pickups,
 * headstock and the grey remainder of the gauge, and thickens what is left — detail that reads at
 * 512 px turns to mush at 16, and a 30/512 stroke lands under one pixel there.
 *
 * Both sit on a filled tile. Earlier versions were transparent with dark artwork, which disappeared
 * against dark title bars and taskbars; the tile guarantees a silhouette on any background.
 *
 * Pre-rendered rather than converted at build time: the JDK cannot read SVG, and the alternative is
 * a rendering dependency the project does not want.
 */
object AppIcon {

    private val SIZES = listOf(16, 24, 32, 48, 64, 128, 256, 512)

    /** Empty if the resources are missing, which must stay survivable — an icon is not worth a crash. */
    val images: List<Image> by lazy {
        SIZES.mapNotNull { size ->
            AppIcon::class.java.getResource("/icons/omega-$size.png")?.let {
                runCatching { ImageIO.read(it) }.getOrNull()
            }
        }
    }

    /**
     * Sets the dock/taskbar icon where the platform has one as a separate concept.
     *
     * Windows takes its taskbar icon from the frame, so this is a no-op there and throws if called
     * unguarded; Linux desktops and macOS use it.
     */
    fun applyToTaskbar() {
        val largest = images.lastOrNull() ?: return
        runCatching {
            if (Taskbar.isTaskbarSupported()) {
                val taskbar = Taskbar.getTaskbar()
                if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) taskbar.iconImage = largest
            }
        }
    }
}
