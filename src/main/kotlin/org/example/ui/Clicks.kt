package org.example.ui

import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/**
 * How a button here learns it was clicked.
 *
 * Not from `mouseClicked`. AWT generates that event only when the pointer did not move at all
 * between the press and the release; a single pixel of drift — a firm press on a stiff button, a
 * trackpad — turns the gesture into press, drag, release and no click ever arrives. Several of the
 * custom buttons used it and were missing one click in so many, which the user reads as a button
 * that does not always work. A native button fires on the *release*, if it lands inside the button
 * that was pressed, and that is what this does.
 */
object Clicks {

    /**
     * Calls [onClick] when the left button is pressed on [component] and released still over it.
     * [enabled] is consulted at both ends, so a button that was disabled between the two does not
     * fire. Dragging off before releasing cancels.
     */
    fun onRelease(
        component: Component,
        enabled: () -> Boolean = { component.isEnabled },
        onClick: (MouseEvent) -> Unit,
    ) {
        component.addMouseListener(object : MouseAdapter() {
            private var pressed = false

            override fun mousePressed(e: MouseEvent) {
                pressed = SwingUtilities.isLeftMouseButton(e) && enabled()
            }

            override fun mouseReleased(e: MouseEvent) {
                val wasPressed = pressed
                pressed = false
                if (wasPressed && SwingUtilities.isLeftMouseButton(e) && enabled() && component.contains(e.point)) {
                    onClick(e)
                }
            }
        })
    }
}
