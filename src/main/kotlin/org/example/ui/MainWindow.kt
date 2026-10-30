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

import org.example.audio.AudioBackend
import org.example.audio.AudioEngine
import org.example.audio.BackendDevice
import org.example.audio.Backends
import org.example.audio.DeviceCapabilities
import org.example.audio.Pairing
import org.example.audio.SampleRates
import org.example.audio.StreamRequest
import org.example.audio.Recorder
import org.example.util.Config
import org.example.util.CpuUsage
import org.example.util.Log
import org.example.dsp.Parameter
import org.example.dsp.rhythm.Feels
import org.example.dsp.rhythm.Pattern
import org.example.dsp.rhythm.TimeSignature
import org.example.dsp.rhythm.Transport
import org.example.dsp.EffectChain
import org.example.dsp.MasterVolume
import org.example.preset.Preset
import org.example.preset.PresetStore
import org.example.preset.Session
import java.nio.file.Files
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FileDialog
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Cursor
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.WindowConstants
import kotlin.system.exitProcess

class MainWindow : JFrame("Omega-Plugin") {

    /**
     * The one master, on the sum of the boards. It lives in the top bar and is on no board: a master
     * that could be dragged ahead of the overdrive would be a different control entirely — which is
     * what it was when it sat first in the chain, and why it no longer does.
     */
    private val master = MasterVolume()

    /**
     * What a board needs from this window. One object serves both boards, because nothing a board
     * asks for differs between them — the status line, the dirty flag and the device bar are shared.
     */
    private val boardHost = object : Board.Host {
        override fun status(text: String, colour: java.awt.Color) = setStatus(text, colour)
        override fun markEdited() = this@MainWindow.markEdited()
        override fun requireStoppedForBoardChange(): Boolean =
            this@MainWindow.requireStoppedForBoardChange()
        override val displayRate: Int get() = this@MainWindow.displayRate
        override fun ampChanged() = announceSplitLoad()
    }

    /**
     * The board, and the second one split mode adds below it.
     *
     * Both exist from the start, whether or not split is on, and both are always in the engine. That
     * is the threading contract's doing rather than generosity: split is switched while the stream
     * runs, and a board constructed, prepared or warmed at that moment would be class loading
     * against the deadline. Idle, the second board costs nothing — the engine does not run it.
     */
    private val board = Board(this, boardHost)
    private val board2 = Board(this, boardHost)

    private val engine = AudioEngine(
        board.chain,
        second = board2.chain,
        post = EffectChain(listOf(master)),
    )

    private val stack = BoardStack(board.view)

    /** Whether the second board is on. `engine.split` is the audio side of the same switch. */
    private var split = false

    /**
     * The fork beside the first board's eye. Struck while there is one board, like the eye is struck
     * while pedals are hidden: the line says what is true now, and pressing it removes it.
     */
    private val splitButton = EffectPanel.IconButton(EffectPanel.IconButton.Kind.SPLIT_OFF) {
        setSplit(!split)
    }.apply { toolTipText = "Split into two boards" }

    /** Which board is which, shown only once there are two. */
    private val boardLabel1 = label("BOARD 1")
    private val boardLabel2 = label("BOARD 2")

    /**
     * Each board's LEVEL: a mini master at the end of that board, in its header, only in split mode.
     * The same compact knob and readout as MASTER, click to mute, so nothing new has to be learned.
     */
    private val levelKnob1 = Knob(board.level.volume, compact = true)
    private val levelKnob2 = Knob(board2.level.volume, compact = true)
    private val levelValue1 = fixedValueLabel()
    private val levelValue2 = fixedValueLabel()

    private val titleBar = TitleBar(this, "Omega-Plugin") { shutdown() }

    // ------------------------------------------------------------- presets

    private val store = PresetStore.default()
    private var presets: MutableList<Preset> = mutableListOf()

    /** The preset currently loaded, or null when the sound came from nowhere in particular. */
    private var loaded: Preset? = null

    /** True once anything has been changed since that preset was loaded or saved. */
    private var edited = false

    /**
     * Failures from the last folder read, held so they can be shown in full on request.
     *
     * Declared here, above the `init` block, and that placement is load-bearing: property
     * initialisers run in declaration order, so a `= emptyList()` sitting below `init` would run
     * *after* the startup read and quietly wipe what it found.
     */
    private var pendingFailures: List<String> = emptyList()


    /**
     * Every backend, including the ones that will not run here, greyed with the reason appended.
     *
     * A hidden option is indistinguishable from a broken build, and the reason string is the whole
     * diagnostic — "no ASIO driver is installed" is an instruction, an absent entry is a mystery.
     */
    private val backendCombo = JComboBox<AudioBackend>().apply {
        toolTipText = "<html>Which audio driver model to use.<br>" +
            "ASIO is the lowest latency and needs a driver from your interface's maker.<br>" +
            "WASAPI exclusive takes sole control of the device.<br>" +
            "Java Sound always works and is never fast.</html>"
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                selected: Boolean,
                focused: Boolean,
            ): Component {
                super.getListCellRendererComponent(list, value, index, selected, focused)
                val backend = value as? AudioBackend
                if (backend == null) {
                    text = " "
                } else {
                    val reason = backend.availability.reason
                    text = if (reason == null) backend.id.displayName
                    else "${backend.id.displayName} — $reason"
                    foreground = if (reason == null) foreground else Theme.DISABLED
                }
                return this
            }
        }
    }

    private val inputCombo = WideComboBox<BackendDevice>()

    /**
     * Under a single-device driver this holds exactly one entry — the input's device — and stays
     * enabled.
     *
     * Locking it with `isEnabled = false` was the obvious move and is wrong twice over: Nimbus paints
     * a disabled combo's value through its own `SynthStyle`, so the box comes out visibly empty and
     * reads as "no output device was found" rather than "the driver owns both directions", and a
     * cell renderer cannot override that because the look-and-feel sets the colours after the
     * renderer returns. A one-item list is just as locked, needs no look-and-feel override, and
     * actually says which device is in use. `BackendDevice.toString()` is its name, so the stock
     * renderer is the right one.
     */
    private val outputCombo = WideComboBox<BackendDevice>()
    /**
     * Master level, in the top bar rather than on the pedal board.
     *
     * Click toggles mute. The knob's own value is never touched by muting — `MasterVolume.enabled`
     * is a separate flag — so unmuting returns to whatever level was set, with no need to stash it.
     */
    private val masterKnob = Knob(master.volume, compact = true).apply {
        toolTipText = "Master volume — drag to adjust, shift for fine, click to mute and unmute"
    }
    private val masterValue = JLabel().apply {
        font = Theme.VALUE_FONT
        // Reserve the widest string this label will ever hold, measured rather than guessed, and pin
        // minimum and maximum as well as preferred. Preferred alone is not enough: JLabel derives its
        // minimum from its current text, so the layout still grows the column when "MUTED" replaces
        // "80 %" and shoves Rescan and Start sideways.
        val metrics = getFontMetrics(font)
        val widest = maxOf(metrics.stringWidth("MUTED"), metrics.stringWidth("100 %")) + 6
        val fixed = Dimension(widest, metrics.height)
        preferredSize = fixed
        minimumSize = fixed
        maximumSize = fixed
    }

    /** The rate the buffer combo converts frames to milliseconds with; follows [rateCombo]. */
    private var displayRate = 44_100

    /**
     * Sample rate, offered only where **both** endpoints accept it.
     *
     * Built like BUFFER and for the same reason: the driver decides what is legal, so a free-form
     * value only produces refusals that look like our bug. It is also a latency control in its own
     * right — the period is frames over rate, so 128 frames is 2.90 ms at 44.1 kHz and 2.67 ms at
     * 48 kHz — which is why it deserves a picker rather than a constant beside 44 100.
     *
     * There is deliberately no resampling behind it. Bridging a 44.1 kHz input to a 48 kHz output
     * would cost more latency and quality than this whole backend bought.
     */
    private val rateCombo = JComboBox<Int>().apply {
        toolTipText = "<html>Sample rate. Only rates both the input and the output accept are " +
            "listed — Omega does not resample between them.<br>A higher rate shortens the callback " +
            "period for the same buffer size, at more CPU per second.<br>Fixed for the life of a " +
            "stream, so it locks while running.</html>"
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                selected: Boolean,
                focused: Boolean,
            ): Component {
                super.getListCellRendererComponent(list, value, index, selected, focused)
                text = (value as? Int)?.let { SampleRates.format(it) } ?: " "
                return this
            }
        }
    }

    /**
     * Buffer size in **frames**, and only ever a value the driver said it would accept.
     *
     * ASIO reports min/max/preferred/granularity and refuses anything else, so a free-form field or
     * a continuous slider here would produce "device refused the format" errors that look like our
     * bug. Both units are always shown, because frames are what the driver takes and milliseconds
     * are what the player feels.
     */
    private val bufferCombo = JComboBox<Int>().apply {
        toolTipText = "<html>Buffer size, in frames — the dominant latency term.<br>" +
            "Round trip is roughly two buffers plus the driver's own fixed overhead.<br>" +
            "Lower is more responsive and more likely to click; the default is whatever the " +
            "driver says it prefers.<br>Fixed for the life of a stream, so it locks while " +
            "running.</html>"
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                selected: Boolean,
                focused: Boolean,
            ): Component {
                super.getListCellRendererComponent(list, value, index, selected, focused)
                val frames = value as? Int
                text = if (frames == null) " "
                else String.format("%d frames · %.1f ms", frames, frames * 1000f / displayRate)
                return this
            }
        }
    }

    // Both neutral, and the same width, so the pair reads as one row of controls. Start carries a
    // lamp instead of a colour: it is the one control whose *state* you want to check at a glance,
    // and a lit lamp says "audio is running" faster than reading whether the word is Start or Stop.
    private val refreshButton = PushButton("Rescan", minimumWidth = 84) { rescanBackends() }
    private val startButton =
        PushButton("Start", minimumWidth = 84, lamp = Theme.GOOD) { toggleEngine() }

    // Red, and only enabled while the stream runs — recording silence because Start was never
    // pressed is a discovery you make after the take.
    private val recordButton =
        PushButton("Rec", minimumWidth = 84, lamp = Theme.BAD) { toggleRecording() }

    /** Settings that outlive the run. Loaded once here, saved by whatever changes them. */
    private val config = Config.default()

    /**
     * Logging comes up before anything else that could be worth logging.
     *
     * Deliberately an `init` block in this position rather than tidier code somewhere later:
     * property initialisers and `init` blocks run in declaration order, so putting it here is what
     * guarantees the device enumeration, the session restore and every failure in either of them are
     * inside the file rather than a few hundred milliseconds ahead of it. The first startup problem
     * to go missing from a log would be the one nobody could explain afterwards.
     */
    init {
        Log.installIn(PresetStore.defaultFolder())
        Log.setEnabled(config.loggingEnabled)
    }

    /**
     * Where the last run left off, read once at construction and applied once at startup.
     *
     * Read here rather than when it is needed, because it has to be captured *before* anything in
     * this window starts changing the very state it describes.
     */
    private val startupSession: Session? = runCatching { Session.fromJsonValue(config.session) }
        .onFailure { Log.error("session", "Could not read the saved session; starting fresh.", it) }
        .getOrNull()

    /**
     * True while [startupSession] is being applied.
     *
     * Restoring drives the same controls the user does, so without this the act of putting the
     * session back would immediately schedule a save of the half-restored state over it.
     */
    private var restoring = false

    /**
     * Coalescing save: every touch restarts it, so a burst of edits costs one write.
     *
     * Ten seconds rather than on-every-change because a knob drag is hundreds of changes and the
     * file is not worth writing hundreds of times; and rather than on-close-only because a session
     * that survives only a clean quit is exactly the one you lose on the day it crashes. Anything
     * discrete — loading a preset, starting the stream, quitting — saves at once and does not wait
     * for this.
     */
    private val sessionSaver = Timer(SESSION_SAVE_DELAY_MS) { saveSession() }.apply { isRepeats = false }

    private var recorder: Recorder? = null

    /** The optional " - drums" twin, open for exactly as long as [recorder] is. */
    private var rhythmRecorder: Recorder? = null

    /** A take whose files are open but which is still waiting for its downbeat. */
    private var armedForCountIn = false

    /**
     * Whether the transport played at any point during the current take.
     *
     * Sampled by the meter timer rather than tracked exactly, because the question it answers is
     * only "would the twin be a duplicate" — and a metronome that ran for less than a 33 ms tick
     * did not put anything in the file worth keeping either.
     */
    private var rhythmPlayedDuringTake = false

    private val status = JLabel(" ")

    /**
     * The real message, kept apart from what the label is showing.
     *
     * While the "Copied" acknowledgement is up, the label's own text is not the message — so a
     * second click would otherwise copy the word "Copied".
     */
    private var statusText = ""
    private var statusColor = Theme.TEXT_DIM
    private var copiedTimer: javax.swing.Timer? = null
    private val transport = JLabel(" ")
    private val meter = LevelMeter()

    // ---- the rhythm row -----------------------------------------------------
    //
    // Declared here rather than beside the device controls because the whole group has to exist
    // before `init` builds the bar, and Kotlin initialises properties in source order.

    /** The clock itself. Owned by the engine, which mixes it after the chain. */
    private val rhythm = engine.rhythm

    /**
     * Tempo as a typed field rather than a knob.
     *
     * A knob is right for a value you find by ear and wrong for one you are told — a song is "at
     * 132", and hunting for 132 by dragging is worse than typing it. Committed on Enter or on focus
     * loss, never per keystroke: a field that applied "1", then "13", then "132" would send the
     * transport through 1 BPM on the way.
     */
    private val tempoField = JTextField("120", 4).apply {
        horizontalAlignment = SwingConstants.CENTER
        toolTipText = "Beats per minute, 30 to 300. Enter, or click away, to apply."
        DarkTextField.style(this, height = PushButton.HEIGHT)
    }

    private val tapButton = PushButton("Tap", minimumWidth = 52) { tapTempo() }

    private val beatLamps = BeatLamps()

    private val signatureCombo = JComboBox(TimeSignature.entries.toTypedArray()).apply {
        toolTipText = "<html>What a bar is.<br>" +
            "6/8 and 12/8 are counted in dotted-quarter pulses, so they show two and four lamps.</html>"
    }

    /**
     * The patterns available inside the chosen signature.
     *
     * Its model is rebuilt whenever SIG changes, which is what makes the two impossible to
     * contradict: a waltz is simply not in the list unless the bar is a 3/4.
     */
    private val feelCombo = JComboBox<Pattern>().apply {
        toolTipText = "<html>What happens inside the bar.<br>" +
            "Click styles are ticks; anything below them is the kit.</html>"
    }

    /**
     * The rhythm bus level, as a `Parameter` so it can reuse [Knob] unchanged.
     *
     * It is not part of any effect and no preset stores it — this is the one place in the app where
     * a `Parameter` exists purely to give a control its range, its skew and its formatting. Cheaper
     * than a second knob widget that does the same arithmetic.
     */
    private val rhythmLevelParameter = Parameter("Level", 0f, 1f, 0.5f, unit = "")

    private val rhythmLevel = Knob(rhythmLevelParameter, compact = true).apply {
        toolTipText = "Level of the click and the kit. Independent of MASTER, which covers the guitar only."
        onChange = {
            rhythm.level = rhythmLevelParameter.value
            touchSession()
        }
    }

    private val playButton =
        PushButton("Play", minimumWidth = 76, lamp = Theme.ACCENT) { toggleRhythm() }

    /**
     * The same overflow control the NAM and IR modules use, not a `PushButton` with a glyph on it.
     *
     * `⋮` is not a label, and putting it in a bordered button treats it as one — it ends up sized
     * like TAP and PLAY, which are labelled actions, and reads as a third thing of that kind.
     * [EffectPanel.MenuButton] is a bare stroked glyph with no fill and no border, which is the
     * distinction this window already draws between an action you read and one you recognise.
     *
     * Reusing it rather than copying its paint also means the popup is built through
     * [DarkMenu.popup] by the component itself, so this one cannot drift out of step with the
     * pedals' menus the way it did when it made its own.
     */
    private val rhythmMenuButton = EffectPanel.MenuButton { menu -> buildRhythmMenu(menu) }.apply {
        toolTipText = "Rhythm settings"
    }

    /** ⋮ settings. Plain fields rather than a class, since each one is a single boolean. */
    private var recordTwin = false
    private var countInEnabled = false
    private var countInBars = 1

    /** Tap-tempo timestamps, newest last. Cleared once the taps stop making a tempo. */
    private val taps = ArrayDeque<Long>()

    // 30 Hz is plenty for a level meter and keeps the EDT idle the rest of the time. The transport
    // readout only changes slowly, so it is refreshed every 10th tick to avoid pointless relayout.
    private var tick = 0

    /** Last logged (xruns, deadlineMisses), so an unchanged stream costs no lines. */
    private var lastHealth = 0 to 0

    /** Share of the machine this process is using. Sampled once a second however often it is read. */
    private val cpu = CpuUsage()

    private val meterTimer = Timer(33) {
        meter.update(engine.outputLevel)
        beatLamps.update(rhythm.displayBeat, rhythm.beatSerial)
        pollCountIn()
        if (recorder != null && rhythm.isRunning) rhythmPlayedDuringTake = true
        if (tick++ % 10 == 0) updateTransport()
        // Every 60 s, and only when a counter actually moved. The complaint this answers is "it was
        // fine and then it started crackling half an hour in", which a start line and a stop line
        // cannot place in time. Rate-limited and change-gated because a clean hour must not cost
        // sixty identical lines — a log nobody will read to the end is not evidence.
        if (tick % HEALTH_TICKS == 0) logStreamHealthIfChanged()
    }

    init {
        defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        // The window draws its own chrome, so the OS draws none. Must be set before pack() makes
        // the frame displayable.
        isUndecorated = true
        contentPane.background = Theme.BACKGROUND
        if (AppIcon.images.isNotEmpty()) iconImages = AppIcon.images

        val root = JPanel(BorderLayout(0, 14)).apply {
            background = Theme.BACKGROUND
            border = BorderFactory.createEmptyBorder(14, 16, 14, 16)
        }
        root.add(buildTopBars(), BorderLayout.NORTH)
        // Scrolled, because a narrow window needs more rows than it has height for. The stack is
        // Scrollable and fills the viewport whenever it fits, so the thumb only appears when the
        // boards genuinely do not. The bar's column is reserved always, though: added on demand it
        // narrowed the viewport by its width and the rows, centred in it, lurched sideways by half
        // of that — exactly when split mode made the window scroll. SlimScrollBarUI fades the thumb
        // in and out instead, so a reserved column shows nothing while there is nothing to scroll.
        root.add(
            JScrollPane(stack).apply {
                border = BorderFactory.createEmptyBorder()
                isOpaque = false
                viewport.isOpaque = false
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                verticalScrollBar.setUI(SlimScrollBarUI())
                verticalScrollBar.preferredSize = Dimension(11, 0)
                verticalScrollBar.isOpaque = false
                verticalScrollBar.unitIncrement = 24
            },
            BorderLayout.CENTER,
        )
        root.add(buildStatusBar(), BorderLayout.SOUTH)

        // The shell's empty border is the resize gutter: a few pixels that no child covers, so a
        // press there reaches WindowResizer rather than a knob. The line border stands in for the
        // frame edge the OS is no longer drawing.
        val shell = JPanel(BorderLayout()).apply {
            background = Theme.BACKGROUND
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.TRACK, 1),
                BorderFactory.createEmptyBorder(
                    WindowResizer.MARGIN,
                    WindowResizer.MARGIN,
                    WindowResizer.MARGIN,
                    WindowResizer.MARGIN,
                ),
            )
            add(titleBar, BorderLayout.NORTH)
            add(root, BorderLayout.CENTER)
        }
        WindowResizer(this).installOn(shell)
        contentPane.add(shell)

        // Arrives on the opening thread, so it marshals. Ignored once the open has settled, or a
        // late frame could overwrite the "Running —" line with a stale percentage.
        engine.onStartProgress = { stage, fraction ->
            SwingUtilities.invokeLater {
                if (starting) {
                    val text = if (fraction < 0f) {
                        "$stage…"
                    } else {
                        "$stage… ${(fraction * 100).toInt()} %"
                    }
                    setStatus(text, Theme.TEXT_DIM)
                }
            }
        }

        engine.onError = { message ->
            // Already off the audio thread — this arrives from a stream's monitor thread, which is
            // the whole reason that thread exists. Logged before the hop to the EDT so the record
            // survives even if the UI is what is wedged.
            Log.error("audio", "Stream stopped by the backend: $message")
            SwingUtilities.invokeLater {
                stopEngine()
                setStatus("Audio stream stopped: $message", Theme.BAD)
            }
        }

        // Clicking off a text field commits it, the way Enter does. Installed on the toolkit once
        // rather than per field, so it also covers the two built on demand — a knob's inline editor
        // and the rename dialog's field — neither of which exists yet.
        DarkTextField.installClickAwayCommit(root)

        // All five together: a bar where some combos are Nimbus silver and others gunmetal reads as
        // a rendering fault rather than a style.
        DarkComboBoxUI.install(
            backendCombo, inputCombo, outputCombo, rateCombo, bufferCombo,
            // The rhythm row's two. A combo left on Nimbus's own UI beside four that are not is the
            // most visible mismatch the window can have, because they sit on the same bar.
            signatureCombo, feelCombo,
        )

        // The popup shows the full device name now, so the closed control need not. Left to itself a
        // combo takes its preferred width from the longest item, and "Headset Microphone (3- Arctis
        // Pro Wireless Chat)" would set the width of the whole window.
        inputCombo.preferredSize = Dimension(190, inputCombo.preferredSize.height)
        outputCombo.preferredSize = Dimension(190, outputCombo.preferredSize.height)

        backendCombo.addActionListener {
            if (populating) return@addActionListener
            refreshDevices()
            touchSession()
        }
        inputCombo.addActionListener {
            if (populating) return@addActionListener
            if (singleDevice) mirrorSingleDeviceSelection() else refreshRateAndBufferChoices()
            updateRateCompatibilityStatus()
            touchSession()
        }
        // The output side is half the rate negotiation, so it has to re-run it too — missing this
        // listener is exactly how the original bug survived: the rate was settled from the input
        // and never revisited when the output changed under it.
        outputCombo.addActionListener {
            if (populating) return@addActionListener
            refreshRateAndBufferChoices()
            updateRateCompatibilityStatus()
            touchSession()
        }
        // BUFFER shows milliseconds computed from the selected rate, so it has to be rebuilt, not
        // merely repainted: the legal frame counts themselves move with the rate on WASAPI.
        rateCombo.addActionListener {
            if (populating) return@addActionListener
            val backend = selectedBackend ?: return@addActionListener
            val device = (inputCombo.selectedItem ?: outputCombo.selectedItem) as? BackendDevice
                ?: return@addActionListener
            displayRate = (rateCombo.selectedItem as? Int) ?: displayRate
            refreshBufferChoices(backend, device, sameDevice = true)
            touchSession()
        }
        // BUFFER had no listener at all, because nothing downstream depended on it — the value is
        // read straight off the combo when the stream opens. It needs one now: the size chosen is
        // part of the setup a session puts back.
        bufferCombo.addActionListener { if (!populating) touchSession() }

        engine.onFormatChanged = { format ->
            // Not an error: this is the driver's own control panel being used while we run. Logged
            // because a buffer size that changed underneath is invisible afterwards, and it explains
            // reports of the setting "not sticking".
            Log.info(
                "audio",
                "Driver changed the format: ${format.bufferFrames} frames at ${format.sampleRate} Hz.",
            )
            SwingUtilities.invokeLater {
                // The user changed the buffer in the driver's own control panel. Mirror it and keep
                // playing; fighting the driver's panel would be the wrong instinct.
                displayRate = format.sampleRate
                bufferCombo.selectedItem = format.bufferFrames
                updateTransport()
            }
        }

        // Each board's eye is its own preference, remembered on its own: the first board can be
        // trimmed to what is on while the second shows everything. Restored before the listener is
        // attached, so putting the saved value back does not count as a change and write it out again.
        board.view.hideDisabled = config.hideDisabledModules
        board.view.onHideDisabledChanged = { on -> config.hideDisabledModules = on }
        board2.view.hideDisabled = config.hideDisabledModulesSecond
        board2.view.onHideDisabledChanged = { on -> config.hideDisabledModulesSecond = on }

        masterKnob.onChange = {
            updateMaster()
            markEdited()
        }
        masterKnob.onClick = {
            master.enabled = !master.enabled
            updateMaster()
            markEdited()
        }
        updateMaster()

        // The split fork sits beside the first board's eye and is the one way in and out of split
        // mode. Each board's LEVEL lives at the right end of its own header, where it is unambiguously
        // that board's. The LEVELs and the board names are split-only: the board fades them in and out
        // with the second board, into a lane that has had room for them from the start.
        board.view.headerLeft.add(splitButton)
        // The names go last in the left end: eye, fork, BOARD 1 — the fork stays beside the eye.
        board.view.headerLeft.add(board.view.splitOnly)
        board2.view.headerLeft.add(board2.view.splitOnly)
        board.view.splitOnly.add(boardLabel1)
        board2.view.splitOnly.add(boardLabel2)
        board.view.headerRight.add(levelGroup(board, levelKnob1, levelValue1))
        board2.view.headerRight.add(levelGroup(board2, levelKnob2, levelValue2))
        updateBoardLevels()

        titleBar.onMenuOpening = { menu -> buildPresetMenu(menu) }
        reloadPresets(announce = true)

        recordButton.isEnabled = false

        // Alt+F4 and a WM_CLOSE from outside still arrive here even without a native close button.
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) = shutdown()
        })

        // The saved driver gets first refusal; if it is gone, this lands on the usual pick and says
        // so. The device bar is settled here rather than later so it is right the moment the window
        // appears — enumerating devices is what startup was already doing.
        refreshBackends(prefer = startupSession?.backend)
        startupSession?.let { restoreDevices(it) }
        runCatching { restoreRhythm(startupSession?.rhythm) }

        pack()
        // Small enough that the board can be driven down to a single column. Both the device bar and
        // the pedal board wrap, so there is no width at which the window merely clips.
        minimumSize = Dimension(380, 320)
        setLocationRelativeTo(null)

        // The sound comes back *after* the window is on screen, and that is deliberate: restoring a
        // NAM capture runs its mandatory warm-up, which is tens to hundreds of milliseconds of doing
        // nothing visible. Ahead of the first paint that reads as an application that will not
        // start; behind it, as one that is still settling.
        if (startupSession != null) SwingUtilities.invokeLater { restoreSound(startupSession) }
    }

    private fun buildDeviceBar(): JPanel {
        val inputGroup = group("INPUT", inputCombo)
        val outputGroup = group("OUTPUT", outputCombo)

        val layout = BarLayout(hgap = 16, vgap = 10)
        // These two absorb the surplus while the bar is one line, which is what it did under
        // GridBagLayout. Once it wraps the rows pack left instead.
        layout.grow(inputGroup, outputGroup)

        return JPanel(layout).apply {
            background = Theme.PANEL
            border = BorderFactory.createEmptyBorder(12, 14, 12, 14)
            add(group("DRIVER", backendCombo))
            add(inputGroup)
            add(outputGroup)
            add(group("RATE", rateCombo))
            add(group("BUFFER", bufferCombo))
            add(
                JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                    isOpaque = false
                    add(label("MASTER"))
                    add(masterKnob)
                    add(masterValue)
                }
            )
            add(
                JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                    isOpaque = false
                    add(refreshButton)
                    add(startButton)
                    add(recordButton)
                }
            )
        }
    }

    /**
     * The two bars, stacked.
     *
     * A second [BarLayout] panel rather than more children in the first one. Appending to the device
     * row would put the tempo field beside BUFFER on a wide window and only separate them once the
     * window narrowed enough to wrap — the layout would decide where the boundary between "the
     * device" and "the metronome" falls, and it would move as the window resized. Two panels means
     * each row wraps within itself and the boundary is fixed.
     */
    private fun buildTopBars(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        add(buildDeviceBar())
        add(buildRhythmBar())
    }

    /**
     * Row two: everything about time rather than about the device.
     *
     * Order follows the sentence a user says to themselves — *how fast, where am I, what shape, how
     * loud, go* — rather than grouping by widget type. TAP sits against the tempo field because it
     * is a second way to set the same number, and ⋮ sits against PLAY because both are about the
     * transport rather than about the pattern.
     */
    private fun buildRhythmBar(): JPanel {
        rebuildFeels(TimeSignature.FOUR_FOUR, select = null)

        tempoField.addActionListener { commitTempo() }
        tempoField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(event: java.awt.event.FocusEvent) = commitTempo()
        })

        signatureCombo.addActionListener {
            if (populating) return@addActionListener
            val signature = signatureCombo.selectedItem as? TimeSignature ?: return@addActionListener
            rhythm.signature = signature
            // Keep the same feel across the change where the new bar has one by that name — "still
            // eighths, now in three" is a real move — and fall back to the plain click where it does
            // not, because it is the one style every signature has.
            rebuildFeels(signature, select = (feelCombo.selectedItem as? Pattern)?.name)
            beatLamps.beats = signature.beatsPerBar
            touchSession()
        }

        feelCombo.addActionListener {
            if (populating) return@addActionListener
            (feelCombo.selectedItem as? Pattern)?.let { rhythm.pattern = it }
            touchSession()
        }

        playButton.isEnabled = false
        playButton.toolTipText = "Start the stream first — the click has no way out until then."

        val layout = BarLayout(hgap = 16, vgap = 10)
        return JPanel(layout).apply {
            background = Theme.PANEL
            border = BorderFactory.createEmptyBorder(0, 14, 12, 14)
            add(
                JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                    isOpaque = false
                    add(label("TEMPO"))
                    add(tempoField)
                    add(label("BPM").apply { foreground = Theme.TEXT_DIM })
                    add(tapButton)
                }
            )
            add(beatLamps)
            add(group("SIG", signatureCombo))
            add(group("FEEL", feelCombo))
            add(
                JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                    isOpaque = false
                    add(label("LEVEL"))
                    add(rhythmLevel)
                }
            )
            add(
                JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                    isOpaque = false
                    add(playButton)
                    add(rhythmMenuButton)
                }
            )
        }
    }

    // ---- the rhythm row's behaviour -----------------------------------------

    /**
     * Rebuilds FEEL for a signature, keeping the chosen style where the new bar also has it.
     *
     * [select] is matched by **name** rather than by index, because the lists are different lengths:
     * 4/4 carries a backbeat click that 3/4 does not, so position 4 means different things in the
     * two. Falling back to the plain click is deliberate over nearest-match guessing — a fallback
     * the user can predict beats one that is usually cleverer and occasionally baffling.
     */
    private fun rebuildFeels(signature: TimeSignature, select: String?) {
        val feels = Feels.forSignature(signature)
        val wanted = feels.firstOrNull { it.name == select } ?: Feels.defaultFor(signature)
        val was = populating
        populating = true
        try {
            feelCombo.model = DefaultComboBoxModel(feels.toTypedArray())
            feelCombo.selectedItem = wanted
        } finally {
            populating = was
        }
        rhythm.pattern = wanted
    }

    /**
     * Applies what was typed, or puts back what is actually set.
     *
     * Anything unreadable restores the transport's own value rather than reporting an error: the
     * field is one control on a crowded bar, and a dialog over a typo would be out of all proportion
     * to it. Out-of-range clamps and shows the clamped figure, so the field never claims a tempo
     * that is not running.
     */
    private fun commitTempo() {
        val typed = tempoField.text.trim().replace(',', '.').toDoubleOrNull()
        val bpm = (typed ?: rhythm.tempoBpm).coerceIn(Transport.MIN_BPM, Transport.MAX_BPM)
        rhythm.tempoBpm = bpm
        showTempo(bpm)
        touchSession()
    }

    private fun showTempo(bpm: Double) {
        // Whole numbers stay whole: tapping gives 128.4 and typing gives 128, and a field that
        // rendered the typed one as "128.0" would look like it had changed what was entered.
        val text = if (bpm == Math.floor(bpm)) bpm.toInt().toString() else String.format("%.1f", bpm)
        if (tempoField.text != text) tempoField.text = text
    }

    /**
     * Tap tempo, averaged over the whole run of taps rather than the last gap.
     *
     * Averaging is what makes it usable: a single interval carries the whole of one tap's timing
     * error, and four taps average it down by roughly a factor of two. [TAP_WINDOW] caps the run so
     * that a tempo change part-way through a session is not held back by taps from a minute ago, and
     * a gap longer than [TAP_TIMEOUT_MS] starts a fresh count — anything slower than that is not a
     * pulse, it is somebody coming back to the button.
     */
    private fun tapTempo() {
        val now = System.nanoTime()
        if (taps.isNotEmpty() && (now - taps.last()) > TAP_TIMEOUT_MS * 1_000_000L) taps.clear()
        taps.addLast(now)
        while (taps.size > TAP_WINDOW) taps.removeFirst()

        if (taps.size < 2) {
            setStatus("Tap again to set the tempo.", Theme.TEXT_DIM)
            return
        }
        val span = (taps.last() - taps.first()).toDouble()
        val bpm = 60.0 * 1_000_000_000.0 * (taps.size - 1) / span
        if (bpm < Transport.MIN_BPM || bpm > Transport.MAX_BPM) {
            taps.clear()
            setStatus("That is outside ${Transport.MIN_BPM.toInt()}–${Transport.MAX_BPM.toInt()} BPM.", Theme.BAD)
            return
        }
        rhythm.tempoBpm = bpm
        showTempo(bpm)
        setStatus("Tempo ${String.format("%.1f", bpm)} BPM from ${taps.size} taps.", Theme.TEXT_DIM)
        touchSession()
    }

    private fun toggleRhythm() {
        if (!engine.isRunning) return
        if (rhythm.isRunning) {
            rhythm.stop()
            beatLamps.clear()
        } else {
            rhythm.start()
        }
        updateRhythmControls()
    }

    /** Keeps PLAY's label, lamp and availability in step with the stream and the transport. */
    private fun updateRhythmControls() {
        val streaming = engine.isRunning
        playButton.isEnabled = streaming
        playButton.toolTipText =
            if (streaming) "Start and stop the metronome."
            else "Start the stream first — the click has no way out until then."
        playButton.text = if (rhythm.isRunning) "Stop" else "Play"
        playButton.lit = rhythm.isRunning
        if (!streaming) beatLamps.clear()
    }

    /**
     * The ⋮ menu: things you set once, against the bar, which holds the things you change while
     * playing.
     *
     * Rebuilt on every open rather than kept around, which is how the locked state of the first row
     * stays true — whether it is locked depends on the FEEL currently selected, and that changes
     * without the menu being involved.
     */
    private fun buildRhythmMenu(menu: JPopupMenu) {
        val content = DarkMenu.content()
        val clickIsTheFeel = (feelCombo.selectedItem as? Pattern)?.isClickOnly ?: true

        content.add(
            DarkMenu.switchRow(
                "Click over drum patterns",
                selected = clickIsTheFeel || rhythm.clickOverDrums,
                enabled = !clickIsTheFeel,
            ) { on ->
                rhythm.clickOverDrums = on
                touchSession()
            }
        )
        if (clickIsTheFeel) {
            content.add(DarkMenu.note("Locked on: the feel you have chosen is already a click."))
        }

        content.add(
            DarkMenu.switchRow("Also record a \" - drums\" copy", recordTwin) { on ->
                recordTwin = on
                touchSession()
            }
        )
        content.add(DarkMenu.note("Off: takes hold the guitar only, and the click is monitor-only."))

        // Built before the row that governs it, so that row's handler can reach it. The length
        // sub-option has to become available the moment count-in is switched on — a menu is a single
        // interaction, and a control that only unlocks after closing and reopening reads as broken.
        val twoBarsRow = DarkMenu.switchRow(
            "     Two bars instead of one",
            selected = countInBars == 2,
            enabled = countInEnabled,
        ) { on ->
            countInBars = if (on) 2 else 1
            touchSession()
        }

        content.add(
            DarkMenu.switchRow("Count-in before recording", countInEnabled) { on ->
                countInEnabled = on
                DarkMenu.setRowEnabled(twoBarsRow, on)
                touchSession()
            }
        )
        content.add(twoBarsRow)
        content.add(DarkMenu.note("Only while the metronome is playing. Record never starts it."))
        menu.add(content)
    }

    /** A label and its control, kept as one unit so wrapping can never separate them. */
    private fun group(text: String, control: JComponent): JPanel = JPanel(BorderLayout(8, 0)).apply {
        isOpaque = false
        add(label(text), BorderLayout.WEST)
        add(control, BorderLayout.CENTER)
    }

    private fun buildStatusBar(): JPanel = JPanel(BorderLayout(12, 0)).apply {
        isOpaque = false
        status.font = Theme.BODY_FONT
        status.foreground = Theme.TEXT_DIM

        // Click the message to copy it. The status line is where every failure lands, and the
        // useful thing to do with a driver's complaint is paste it somewhere — a search, a bug
        // report, a message to somebody who knows what it means. Retyping it by eye is exactly the
        // point at which the detail that mattered gets lost.
        val clicks = object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (overStatusText(event)) copyStatus()
            }

            // Only the text itself is clickable, not the empty width of the label. A whole-width
            // hit area would copy on a click in what looks like blank background.
            override fun mouseMoved(event: MouseEvent) {
                status.cursor = if (overStatusText(event)) {
                    Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                } else {
                    Cursor.getDefaultCursor()
                }
            }
        }
        status.addMouseListener(clicks)
        status.addMouseMotionListener(clicks)

        add(status, BorderLayout.CENTER)
        add(
            JPanel(FlowLayout(FlowLayout.RIGHT, 12, 0)).apply {
                isOpaque = false
                // Monospaced so the numbers do not shuffle the layout as they change, and so the
                // readout stays legible where no proportional font is guaranteed to exist.
                transport.font = Font(Font.MONOSPACED, Font.PLAIN, 11)
                transport.foreground = Theme.TEXT_DIM
                add(transport)
                add(label("OUT"))
                add(meter)
            },
            BorderLayout.EAST,
        )
    }

    /**
     * Transport health, polled off the volatile counters the audio thread writes.
     *
     * Worth having on screen: "it crackles" is unfalsifiable, a dropout count is not. Latency is
     * measured rather than assumed because the driver keeps a lead of its own on top of what the
     * engine primes.
     */
    private fun updateTransport() {
        val format = engine.format
        if (!engine.isRunning || format == null) {
            transport.text = " "
            transport.toolTipText = null
            return
        }
        val dropouts = engine.xruns + engine.deadlineMisses
        // Labelled "driver" on purpose. This is what the driver claims, which includes its own
        // safety offsets and excludes the converters; --measure with a lead from output back to
        // input is the only thing that produces the other kind of number.
        // Elapsed time first while recording: four minutes in, it is the only number that matters.
        val take = recorder?.let {
            val seconds = it.elapsedSeconds.toInt()
            String.format("REC %d:%02d   ", seconds / 60, seconds % 60)
        }.orEmpty()
        // Omitted rather than shown as 0 % until there are two samples to subtract — a CPU readout
        // that says nothing is honest, one that says zero on a machine doing work is not.
        val machine = cpu.sample(System.nanoTime())
        // One decimal, because whole percent is useless here: this is a share of *every* core, and a
        // realtime audio process that is behaving uses a fraction of one of them. On a twelve-core
        // machine an entirely healthy chain rounds to 0 %, which reads as "not measured".
        val cpuText = if (machine < 0f) "" else String.format("CPU %.1f%%   ", machine * 100f)
        transport.text = String.format(
            "%s%.1f ms driver   DSP %2.0f%%   %s%d dropout%s",
            take,
            format.reportedRoundTripMs,
            (engine.loadPercent * 100f).coerceIn(0f, 999f),
            cpuText,
            dropouts,
            if (dropouts == 1) "" else "s",
        )
        transport.foreground = if (dropouts == 0) Theme.TEXT_DIM else Theme.BAD
        transport.toolTipText = String.format(
            "<html><b>%.1f ms round trip as %s reports it</b> — in %d + out %d frames" +
                "%s, at %d Hz.<br>" +
                "Driver safety offsets are included; converter latency is not, so treat it as a " +
                "good estimate rather than the truth.<br>" +
                "Callback period %.2f ms (%d frames). DSP is using %.0f%% of it, worst %.2f ms.<br>" +
                "<b>DSP and CPU are not the same measurement.</b> DSP is the share of one callback " +
                "period the chain consumes, and is what predicts a dropout. CPU is the share of all " +
                "%d cores the whole application uses, as Task Manager counts it — so on this " +
                "machine a chain at half its deadline is only a few per cent of the CPU.<br>" +
                "Device xruns %d, deadline misses %d. Both should stay at 0.<br>" +
                "For a true cable-to-cable figure, patch output to input and run " +
                "<code>--measure</code>.</html>",
            format.reportedRoundTripMs,
            engine.backendId?.displayName ?: "the driver",
            format.inputLatencyFrames,
            format.outputLatencyFrames,
            if (format.adapterLatencyFrames > 0) " + ${format.adapterLatencyFrames} added here" else "",
            format.sampleRate,
            format.callbackPeriodMs,
            format.bufferFrames,
            engine.loadPercent * 100f,
            engine.worstCallbackMicros / 1000f,
            Runtime.getRuntime().availableProcessors(),
            engine.xruns,
            engine.deadlineMisses,
        )
    }


    /**
     * Records that the sound has moved away from whatever was last loaded or saved.
     *
     * Tracked even with no preset loaded: those edits are still work that loading one would throw
     * away, and that is exactly what the confirmation before [load] is guarding.
     */
    private fun markEdited() {
        // Outside the early return, and that is the point: the *first* change flips the dot, but
        // every change after it is still a change the session has to catch up with.
        touchSession()
        if (edited) return
        edited = true
        updateTitle()
    }

    private fun updateTitle() {
        val preset = loaded
        titleBar.subtitle = when {
            preset == null -> null
            edited -> "${preset.name} •"
            else -> preset.name
        }
    }

    /**
     * Builds the preset half of the hamburger menu, fresh each time it opens.
     *
     * Save is enabled only with a preset loaded *and* something changed, so the entry answers "is
     * there anything worth saving" rather than merely "is a preset open".
     */
    private fun buildPresetMenu(menu: JPopupMenu) {
        val save = DarkMenu.submenu("Save Preset")
        save.add(DarkMenu.item("Save", enabled = loaded != null && edited) { saveOverExisting() })
        save.add(DarkMenu.item("Save As…") { saveAsNew() })
        menu.add(save)

        val load = DarkMenu.submenu("Load Preset", enabled = presets.isNotEmpty())
        if (presets.isEmpty()) {
            load.add(DarkMenu.item("No presets saved", enabled = false))
        } else {
            for (preset in presets.sortedBy { it.name.lowercase() }) {
                val entry = DarkMenu.submenu(preset.name)
                entry.add(DarkMenu.item("Load") { load(preset) })
                entry.add(
                    DarkMenu.item("Delete") { confirmDelete(preset) }
                        .apply { foreground = DarkMenu.DANGER }
                )
                load.add(entry)
            }
        }
        menu.add(load)

        menu.add(DarkMenu.item("Import Preset…") { importPresets() })
        menu.add(DarkMenu.item("Export Preset") { exportPresets() })
        menu.add(DarkMenu.item("Select Recording Destination…") { chooseRecordingFolder() })
        menu.add(DarkMenu.item("Open Recordings Folder") { openRecordingFolder() })
        buildLoggingRows(menu)
        // Only offered when there is something to explain, so it is never a dead entry.
        if (pendingFailures.isNotEmpty()) {
            menu.add(
                DarkMenu.item("${pendingFailures.size} preset file(s) failed to load…") {
                    showLoadFailures()
                }.apply { foreground = DarkMenu.DANGER }
            )
        }
    }

    /**
     * The logging switch, and the way to get at what it wrote.
     *
     * A switch rather than a menu item, because this is a state you are meant to read at a glance:
     * the question a user asks here is "is it recording my problem right now", and an item that just
     * says "Logging" answers that only by being clicked. [DarkMenu.switchRow] already exists for
     * exactly this, so it looks like the NAM rate switch rather than like a second idea.
     *
     * The note underneath is not decoration. Someone about to send a log has to find the file, and
     * "it's in your app data" is not a location — so the folder is named, and there is a row that
     * opens it. Deliberately **not** [markEdited]/[touchSession]: this is a diagnostic belonging to
     * the installation, and flagging the patch dirty would offer to save it into a preset, where it
     * has no business being.
     */
    private fun buildLoggingRows(menu: JPopupMenu) {
        val content = DarkMenu.content()
        val note = DarkMenu.note(loggingNote(Log.enabled)) as JLabel
        // Sized for the longer of the two messages, once, before the popup is shown. A JPopupMenu
        // takes its size when it opens and does not grow while it is up, so a label laid out for the
        // shorter string would clip the other one to an ellipsis the moment the switch moved.
        val metrics = note.getFontMetrics(note.font)
        val widest = maxOf(
            metrics.stringWidth(loggingNote(true)),
            metrics.stringWidth(loggingNote(false)),
        )
        note.preferredSize = Dimension(
            widest + DarkMenu.PADDING.left * 2,
            note.preferredSize.height,
        )
        content.add(
            DarkMenu.switchRow("Logging", Log.enabled) { on ->
                setLogging(on)
                // The row is what the switch is read against, so it has to answer immediately. The
                // menu is rebuilt on every open, which meant the note was a snapshot taken before
                // the click and stayed wrong until the menu was closed and opened again.
                note.text = loggingNote(on)
                note.repaint()
            }
        )
        content.add(note)
        menu.add(content)
        menu.add(DarkMenu.item("Open Logs Folder") { openLogsFolder() })
    }

    private fun loggingNote(on: Boolean): String =
        // The platform's separator: `logs\omega.txt` is right on Windows and wrong on Linux.
        if (on) "Writing to logs${java.io.File.separator}omega.txt in the settings folder."
        else "Off. Turn on to record a file for troubleshooting."

    /**
     * Applies the switch, remembers it, and says what happened.
     *
     * The order matters on the way on: [Log.setEnabled] writes its banner — version, OS, JVM,
     * collector — as the first thing in the file, so the line recording the user's own click has to
     * come after it rather than before an empty header.
     */
    private fun setLogging(on: Boolean) {
        if (on) {
            Log.setEnabled(true)
            Log.info("log", "Logging turned on from the menu.")
        } else {
            // Log.setEnabled(false) writes its own closing line and then drains, so nothing is said
            // here first — a "turning off" line would be the last thing in the file either way.
            Log.setEnabled(false)
        }
        config.loggingEnabled = on
        setStatus(
            if (on) "Logging on — writing to ${Log.file}" else "Logging off.",
            if (on) Theme.GOOD else Theme.TEXT_DIM,
        )
    }

    /**
     * Opens the logs folder, creating it if the switch has never been on.
     *
     * Creating it is the difference between "here is where your log will be" and a file browser
     * error, and someone reaching for this is usually mid-way through being asked for a log by
     * somebody else. Off the EDT for the same reason as [exportPresets].
     */
    private fun openLogsFolder() {
        val folder = Log.folder
        if (folder == null) {
            setStatus("The log folder is not available.", Theme.BAD)
            return
        }
        try {
            Files.createDirectories(folder)
        } catch (e: Exception) {
            setStatus("Could not create $folder: ${e.message ?: e.javaClass.simpleName}", Theme.BAD)
            return
        }
        setStatus("Logs are in $folder", Theme.TEXT_DIM)
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            return
        }
        Thread({ runCatching { Desktop.getDesktop().open(folder.toFile()) } }, "omega-open-logs")
            .apply { isDaemon = true }
            .start()
    }

    /**
     * Imports from one or more files chosen in the OS's own file browser.
     *
     * [FileDialog] rather than `JFileChooser`: it is the platform's real dialog, so it looks and
     * behaves like every other file picker on the machine, and it needs none of the theming the
     * Swing one would.
     *
     * A name already in use is suffixed rather than replaced or skipped — importing should never
     * quietly overwrite a sound you already had, nor silently drop the one arriving.
     */
    private fun importPresets() {
        val chooser = FileDialog(this, "Import presets", FileDialog.LOAD).apply {
            isMultipleMode = true
            file = "*.json"
        }
        chooser.isVisible = true

        val files = chooser.files ?: return
        if (files.isEmpty()) return

        var imported = 0
        val problems = mutableListOf<String>()
        for (chosen in files) {
            val incoming = try {
                PresetStore.readAll(chosen.toPath())
            } catch (e: Exception) {
                problems += "\"${chosen.name}\" ${e.message ?: e.javaClass.simpleName}"
                continue
            }
            for (preset in incoming) {
                val renamed = preset.copy(name = availableName(preset.name))
                try {
                    store.save(renamed)
                    presets += renamed
                    imported++
                } catch (e: Exception) {
                    problems += "\"${renamed.name}\" could not be written: " +
                        (e.message ?: e.javaClass.simpleName)
                }
            }
        }

        // Every failure named, every reason given. "Some files could not be imported" is not an
        // error message, it is an apology.
        if (problems.isNotEmpty()) {
            MessageDialog.show(
                owner = this,
                title = "Import",
                heading = if (imported > 0) {
                    "Imported $imported preset(s), but ${problems.size} could not be read"
                } else {
                    "Nothing could be imported"
                },
                lines = problems,
            )
        }
        if (imported > 0) setStatus("Imported $imported preset(s).", Theme.GOOD)
    }


    /** Shows the full detail of any preset files that failed to load. */
    private fun showLoadFailures() {
        if (pendingFailures.isEmpty()) {
            setStatus("All preset files loaded cleanly.", Theme.TEXT_DIM)
            return
        }
        MessageDialog.show(
            owner = this,
            title = "Presets",
            heading = "${pendingFailures.size} preset file(s) could not be loaded",
            lines = pendingFailures,
        )
    }

    /** [wanted], or the first "wanted (n)" that is free. */
    private fun availableName(wanted: String): String {
        if (presets.none { it.name.equals(wanted, ignoreCase = true) }) return wanted
        var suffix = 2
        while (presets.any { it.name.equals("$wanted ($suffix)", ignoreCase = true) }) suffix++
        return "$wanted ($suffix)"
    }

    /**
     * Opens the folder the presets live in, so they can be copied out.
     *
     * On a background thread because `Desktop.open` hands off to the desktop environment and can
     * block for seconds — on the EDT that would freeze the window, including the audio meter.
     */
    private fun exportPresets() {
        val folder = store.folder
        try {
            Files.createDirectories(folder)
        } catch (e: Exception) {
            setStatus("Could not create $folder: ${e.message ?: e.javaClass.simpleName}", Theme.BAD)
            return
        }

        setStatus("Presets are in $folder", Theme.TEXT_DIM)
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            // Common on Linux without a desktop helper installed. The path above is still the answer.
            return
        }
        Thread({
            runCatching { Desktop.getDesktop().open(folder.toFile()) }.onFailure {
                SwingUtilities.invokeLater { setStatus("Presets are in $folder", Theme.TEXT_DIM) }
            }
        }, "omega-open-folder").apply { isDaemon = true }.start()
    }

    private fun saveOverExisting() {
        val current = loaded ?: return
        store(captureSound(current.name))
    }

    /**
     * The sound as it stands: the first board with the master beside it, and the second board nested
     * inside when split is on.
     *
     * With split off the second board is not written. It keeps its settings for as long as the
     * program runs, so switching split off and on again returns what was there — but a preset or a
     * session saved with one board describes one board, and reads exactly as it did before split
     * existed.
     */
    private fun captureSound(name: String): Preset = Preset.capture(
        name,
        board.order,
        listOf(master) + board.allEffects(),
        board.loadedFileRefs(),
        split = if (split) board2.captureSecond() else null,
    )

    /**
     * Puts a sound onto the boards, the master and the split switch, with the stream stopped.
     * Returns a sentence for each file that could not be found.
     */
    private fun applySound(sound: Preset): List<String> {
        val missing = board.apply(sound).toMutableList()
        // The master is in the outer preset's maps beside the first board's pedals, and is applied on
        // its own because it is on no board. An unmentioned master comes back on — see Preset.applyTo.
        sound.applyTo(listOf(master))
        updateMaster()
        val second = sound.split
        if (second != null) {
            missing += board2.apply(second)
            setSplit(true, fromUser = false)
        } else {
            setSplit(false, fromUser = false)
        }
        updateBoardLevels()
        return missing
    }

    private fun saveAsNew() {
        val name = TextPrompt.ask(this, "Save preset", "Name") ?: return
        if (name.isBlank()) return
        val existing = presets.firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (existing != null) {
            val replace = ConfirmDialog.ask(
                owner = this,
                title = "Replace preset",
                message = "Replace \"${existing.name}\"?",
                detail = "The preset saved under that name will be overwritten.",
                confirmText = "Replace",
                confirmColour = Theme.ACCENT_SOFT,
            )
            if (!replace) return
        }
        store(captureSound(name))
    }

    /**
     * Re-reads the folder.
     *
     * A file that will not load is reported rather than skipped: silently showing four presets when
     * there are five on disk is the kind of thing you only notice when you go looking for the fifth.
     */
    private fun reloadPresets(announce: Boolean) {
        val listing = store.list()
        presets = listing.presets.toMutableList()
        if (!announce || listing.failures.isEmpty()) return

        val count = listing.failures.size
        setStatus(
            "$count preset file(s) could not be loaded — see the menu's Import for details.",
            Theme.BAD,
        )
        pendingFailures = listing.failures.map { it.toString() }
    }

    /** Writes one preset to its own file. */
    private fun store(preset: Preset) {
        presets.removeAll { it.name.equals(preset.name, ignoreCase = true) }
        presets += preset
        try {
            store.save(preset)
            loaded = preset
            edited = false
            updateTitle()
            saveSession()
            setStatus("Saved preset \"${preset.name}\" to ${store.folder}.", Theme.GOOD)
        } catch (e: Exception) {
            Log.error("preset", "Could not save \"${preset.name}\" to ${store.folder}.", e)
            setStatus(
                "Could not save \"${preset.name}\": ${e.message ?: e.javaClass.simpleName}",
                Theme.BAD,
            )
        }
    }

    /**
     * Applies a preset, restarting the stream around it if one is running.
     *
     * The order cannot change under a live stream — delay lines and reverb tails carried into a new
     * position are audible — so the engine is stopped, the chain rewired, and the same devices
     * reopened. That costs a short silence, which is the trade agreed for being able to load at any
     * time.
     */
    private fun load(preset: Preset) {
        if (edited && !confirmDiscard(preset)) return

        val wasRunning = engine.isRunning
        if (wasRunning) stopEngine()

        val missing = applySound(preset)

        loaded = preset
        edited = false
        updateTitle()
        saveSession()

        if (wasRunning) {
            toggleEngine()
            if (!engine.isRunning) {
                setStatus("Loaded \"${preset.name}\", but the stream did not restart.", Theme.BAD)
                return
            }
        }
        if (missing.isNotEmpty()) {
            setStatus(missing.joinToString("  "), Theme.BAD)
        } else {
            setStatus("Loaded preset \"${preset.name}\".", Theme.GOOD)
        }
    }


    /**
     * Asks before a load throws away edits.
     *
     * Red, like Delete: the work is gone either way, and the button that loses it should look the
     * same whichever it is. Cancel stays neutral, so the destructive choice is the one that has to
     * be aimed at.
     */
    private fun confirmDiscard(incoming: Preset): Boolean {
        val current = loaded
        return ConfirmDialog.ask(
            owner = this,
            title = "Unsaved changes",
            message = if (current != null) {
                "Discard changes to \"${current.name}\"?"
            } else {
                "Discard unsaved changes?"
            },
            detail = "Loading \"${incoming.name}\" will replace the current settings.",
            confirmText = "Discard and load",
            confirmColour = Theme.BAD,
        )
    }

    private fun confirmDelete(preset: Preset) {
        val ok = ConfirmDialog.ask(
            owner = this,
            title = "Delete preset",
            message = "Delete \"${preset.name}\"?",
            detail = "This cannot be undone.",
            confirmText = "Delete",
        )
        if (!ok) return

        try {
            store.delete(preset)
        } catch (e: Exception) {
            setStatus(
                "Could not delete \"${preset.name}\": ${e.message ?: e.javaClass.simpleName}",
                Theme.BAD,
            )
            return
        }
        presets.removeAll { it.name == preset.name }
        if (loaded?.name == preset.name) {
            loaded = null
            edited = false
            updateTitle()
            // The sound stays exactly as it is; only the name it was going under has gone. Saved at
            // once so the session cannot point at a preset that is no longer on disk.
            saveSession()
        }
        setStatus("Deleted preset \"${preset.name}\".", Theme.TEXT_DIM)
    }

    /** The single teardown path: the title bar's close button, Alt+F4 and WM_CLOSE all land here. */
    private fun shutdown() {
        meterTimer.stop()
        // Before anything is torn down, while the combos still hold the selection they are being
        // asked about. A pending coalesced save is superseded rather than raced.
        saveSession()
        // Closing the window mid-take must still finish the file, or the header never gets its
        // lengths and the recording opens as empty.
        finishRecording()
        engine.stop()
        // Under an exclusive-mode backend a device left held can stay stuck until a reboot, so this
        // is more important than it used to be, not less.
        Backends.shutdown()
        // After the devices are released, so a backend that hangs on the way out is recorded as
        // having been reached rather than leaving the log ending at "shutting down".
        Log.shutdown()
        dispose()
        // Disposing the last window does not reliably end the JVM here, and a surviving process
        // keeps the capture device open. Exit explicitly once the lines are closed.
        exitProcess(0)
    }

    /** Keeps the master readout and the knob's muted paint in step with `MasterVolume.enabled`. */
    private fun updateMaster() {
        val muted = !master.enabled
        masterKnob.muted = muted
        masterValue.text = if (muted) "MUTED" else master.volume.formatted()
        masterValue.foreground = if (muted) Theme.BAD else Theme.TEXT
    }

    // ------------------------------------------------------------------ split

    /**
     * Switches the second board in or out.
     *
     * Allowed while the stream runs, unlike every other change to what a board holds, because
     * nothing is being added to a chain: the second board is always prepared and warmed, and the
     * engine fades its share of the output in and out rather than switching it. What changes on the
     * audio side is three volatile stores — each board's LEVEL waking up, and the engine's flag.
     *
     * [fromUser] separates the press from a preset or a session putting the switch back. Only the
     * press dirties the sound and speaks.
     */
    private fun setSplit(on: Boolean, fromUser: Boolean = true) {
        if (split == on) return
        split = on
        if (on) {
            // The second board has been idle with whatever its delay and reverb last held. That is
            // not part of the sound being switched to, and this is the one moment it can be cleared
            // without the audio thread being inside it.
            if (!engine.secondRunning) board2.chain.reset()
            board.level.active = true
            board2.level.active = true
            engine.split = true
        } else {
            engine.split = false
            board.level.active = false
            board2.level.active = false
        }
        splitButton.kind =
            if (on) EffectPanel.IconButton.Kind.SPLIT else EffectPanel.IconButton.Kind.SPLIT_OFF
        splitButton.toolTipText = if (on) "Back to one board" else "Split into two boards"
        board.view.showSplitControls(on)
        board2.view.showSplitControls(on)
        if (on) stack.showSecond(board2.view) else stack.hideSecond()
        updateBoardLevels()

        if (!fromUser) return
        markEdited()
        val warning = splitLoadWarning()
        when {
            warning != null -> setStatus(warning, Theme.BAD)
            on -> setStatus(
                "Split on — two boards, both heard. LEVEL on each sets its share; MASTER covers both.",
                Theme.GOOD,
            )
            else -> setStatus("Split off. The second board keeps its settings until you quit.", Theme.TEXT_DIM)
        }
    }

    /**
     * Said when both boards carry an amp capture at a buffer that may not hold them.
     *
     * Measured rather than guessed: one capture at 64 frames uses about 98 % of the callback period
     * — see CLAUDE.md — so two cannot fit at 64 and are marginal at 128. 256 is where there is room.
     */
    private fun splitLoadWarning(): String? {
        if (!split || board.nam.modelName == null || board2.nam.modelName == null) return null
        val frames = engine.format?.bufferFrames ?: (bufferCombo.selectedItem as? Int) ?: return null
        if (frames >= SPLIT_NAM_FRAMES) return null
        return "Both boards carry an amp capture. At $frames frames that may not keep up — " +
            "set BUFFER to $SPLIT_NAM_FRAMES or more."
    }

    /** After a capture arrives or leaves on either board. */
    private fun announceSplitLoad() {
        splitLoadWarning()?.let { setStatus(it, Theme.BAD) }
    }

    private fun requireStoppedForBoardChange(): Boolean {
        if (!engine.isRunning) return true
        setStatus("Stop the stream to add or remove modules.", Theme.BAD)
        return false
    }

    private fun updateBoardLevels() {
        updateBoardLevel(board, levelKnob1, levelValue1)
        updateBoardLevel(board2, levelKnob2, levelValue2)
    }

    /** Keeps a board's LEVEL readout and knob in step with its mute, as [updateMaster] does. */
    private fun updateBoardLevel(b: Board, knob: Knob, value: JLabel) {
        val muted = !b.level.enabled
        knob.muted = muted
        value.text = if (muted) "MUTED" else b.level.volume.formatted()
        value.foreground = if (muted) Theme.BAD else Theme.TEXT
    }

    /** LEVEL, its knob and its readout, as one unit for a board's header. */
    private fun levelGroup(b: Board, knob: Knob, value: JLabel): JPanel {
        knob.toolTipText =
            "This board's level in the mix — drag to adjust, shift for fine, click to mute and unmute"
        knob.onChange = {
            updateBoardLevel(b, knob, value)
            markEdited()
        }
        knob.onClick = {
            b.level.enabled = !b.level.enabled
            updateBoardLevel(b, knob, value)
            markEdited()
        }
        return JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(label("LEVEL"))
            add(knob)
            add(value)
        }
    }

    /**
     * A readout that reserves the widest string it will ever hold, measured rather than guessed, with
     * minimum and maximum pinned as well as preferred. Preferred alone is not enough: JLabel derives
     * its minimum from its current text, so the layout still grows the column when "MUTED" replaces
     * "80 %" and shoves whatever is beside it sideways.
     */
    private fun fixedValueLabel(): JLabel = JLabel().apply {
        font = Theme.VALUE_FONT
        val metrics = getFontMetrics(font)
        val widest = maxOf(metrics.stringWidth("MUTED"), metrics.stringWidth("100 %")) + 6
        val fixed = Dimension(widest, metrics.height)
        preferredSize = fixed
        minimumSize = fixed
        maximumSize = fixed
    }

    private fun label(text: String) = JLabel(text).apply {
        font = Theme.LABEL_FONT
        foreground = Theme.TEXT_DIM
    }


    /** Guards the combo listeners while a model is being swapped underneath them. */
    private var populating = false

    /** True when the selected backend's driver owns both directions (ASIO, and later JACK). */
    private var singleDevice = false

    /** Which device the buffer list currently describes, so a device change resets the default. */
    private var lastBufferDeviceId: String? = null

    private val selectedBackend: AudioBackend? get() = backendCombo.selectedItem as? AudioBackend

    /**
     * First run picks the lowest-ranked backend that is available *and* can see a device — so on a
     * machine with an ASIO driver installed that lands on ASIO, and on one where the natives failed
     * to load it lands on Java Sound with the reason visible in the combo rather than silently.
     */
    private fun refreshBackends(prefer: String? = null) = applyScan(scan(prefer))

    /** Everything a scan found. Assembled off the event thread, so it holds no Swing state. */
    private class Scan(
        val backends: List<AudioBackend>,
        val chosen: AudioBackend,
        val devices: List<BackendDevice>,
    )

    /**
     * The slow half of a refresh, and the reason [rescanBackends] exists: **no Swing here**.
     *
     * Rescan means "look again", and a backend that answers from a cache is not looking. WASAPI
     * probes each endpoint by opening it, and kept that answer for the life of the process — so a
     * user who was told their input and output share no sample rate, went and changed the output's
     * rate in Windows Sound settings, and came back to press this button, got the same refusal until
     * they restarted the app. Discarding first is what makes the button honest.
     *
     * Looking again is not cheap. `AsioBackend.devices()` opens, inits and releases every registered
     * driver on every call — measured at **~700 ms on this machine**, and this does it twice, once
     * inside [Backends.preferred] and once for the chosen backend, for **~1.4 s** in total. That is a
     * frozen window if it happens on the event thread.
     */
    private fun scan(prefer: String?): Scan {
        Backends.rescan()
        val all = Backends.all()
        // A saved driver gets first refusal, but only if it can still run: honouring a preference
        // for a backend whose driver has since been uninstalled would open on a dead selection.
        val wanted = prefer?.let { name ->
            all.firstOrNull { it.id.name == name && it.availability.isAvailable }
        }
        val chosen = wanted ?: Backends.preferred()
        val devices =
            if (chosen.availability.isAvailable) runCatching { chosen.devices() }.getOrDefault(emptyList())
            else emptyList()
        return Scan(all, chosen, devices)
    }

    /** The fast half. Event thread only — [populating] and the combo models are its state. */
    private fun applyScan(scan: Scan) {
        populating = true
        backendCombo.model = DefaultComboBoxModel(scan.backends.toTypedArray())
        backendCombo.selectedItem = scan.chosen
        populating = false
        refreshDevices(scan.devices)
    }

    /** True from the click until the worker's result has been applied; blocks a second scan. */
    private var scanning = false

    /**
     * The Rescan button: the enumeration runs on a worker, the way `engine.start` does.
     *
     * The status line the user asked for is the visible half; putting the scan off the event thread
     * is what makes it appear at all, since a synchronous scan repaints nothing between "Scanning"
     * and its replacement — the window would simply freeze for a second and then show the outcome.
     *
     * Start is disabled alongside Rescan for the duration, so no stream can come up underneath the
     * combo models this is about to replace. Both come back in [rescanFinished], which runs whether
     * the scan succeeded or threw.
     */
    private fun rescanBackends() {
        // Enumerating opens every driver, which several refuse while one of them is streaming — the
        // button is already hidden behind setDeviceControlsEnabled, and this keeps that true.
        if (scanning || engine.isRunning) return
        scanning = true
        refreshButton.isEnabled = false
        startButton.isEnabled = false
        setStatus("Scanning for audio devices…", Theme.TEXT_DIM)

        val began = System.nanoTime()
        Thread({
            val result = runCatching { scan(prefer = null) }
            SwingUtilities.invokeLater { rescanFinished(result, began) }
        }, "omega-rescan").apply { isDaemon = true }.start()
    }

    /** Back on the event thread with whatever the scan found — or with why it did not. */
    private fun rescanFinished(result: Result<Scan>, began: Long) {
        scanning = false
        refreshButton.isEnabled = true
        result
            .onSuccess {
                Log.info(
                    "backends",
                    "Rescan: ${it.devices.size} device(s) on ${it.chosen.id.displayName} " +
                        "in ${(System.nanoTime() - began) / 1_000_000} ms.",
                )
                // Names what was found, or why Start is still unavailable. Either way the
                // "Scanning…" line is replaced by an outcome rather than by nothing.
                applyScan(it)
            }
            .onFailure {
                startButton.isEnabled = false
                setStatus(
                    "Could not scan for audio devices: ${it.message ?: it.javaClass.simpleName}",
                    Theme.BAD,
                )
                Log.warn("backends", "Rescan failed: ${it.javaClass.simpleName}: ${it.message ?: "no detail"}")
            }
    }

    /**
     * [prefetched] is the device list a [scan] already paid for; without it this enumerates again,
     * which on ASIO is another ~700 ms of opening drivers.
     */
    private fun refreshDevices(prefetched: List<BackendDevice>? = null) {
        val backend = selectedBackend
        if (backend == null || !backend.availability.isAvailable) {
            populating = true
            inputCombo.model = DefaultComboBoxModel()
            outputCombo.model = DefaultComboBoxModel()
            bufferCombo.model = DefaultComboBoxModel()
            populating = false
            startButton.isEnabled = false
            setStatus(backend?.availability?.reason ?: "No audio backend is available.", Theme.BAD)
            return
        }

        val devices = prefetched ?: runCatching { backend.devices() }.getOrDefault(emptyList())
        val inputs = devices.filter { it.inputChannels > 0 }
        val outputs = devices.filter { it.outputChannels > 0 }
        val single = pairingOf(backend, devices) == Pairing.SINGLE_DEVICE

        populating = true
        inputCombo.model = DefaultComboBoxModel(inputs.toTypedArray())
        outputCombo.model = DefaultComboBoxModel(outputs.toTypedArray())
        populating = false

        // One driver, both directions: a free output choice would be a lie, so the list collapses
        // to the one device that is actually in use.
        singleDevice = single
        outputCombo.toolTipText = if (single)
            "${backend.id.displayName} drivers own both directions, so the output is the input's device."
        else null
        if (single) mirrorSingleDeviceSelection()

        refreshRateAndBufferChoices()

        val usable = inputs.isNotEmpty() && outputs.isNotEmpty()
        when {
            !usable -> {
                startButton.isEnabled = false
                setStatus(
                    if (backend.id.displayName.startsWith("ASIO"))
                        "No ASIO device is available. Check the interface is connected and its driver installed."
                    else
                        "No usable audio device on one side. On Linux, check the card is not held exclusively " +
                            "by PulseAudio or PipeWire.",
                    Theme.BAD,
                )
            }
            // Not a failure to report after the fact: Start is simply unavailable, and the reason
            // names the two devices and the rates each of them actually does.
            blockingProblem != null -> {
                startButton.isEnabled = false
                setStatus(blockingProblem!!, Theme.BAD)
            }
            else -> {
                startButton.isEnabled = true
                setStatus(
                    "${inputs.size} input(s), ${outputs.size} output(s) on ${backend.id.displayName}.",
                    Theme.TEXT_DIM,
                )
            }
        }
    }

    private fun pairingOf(backend: AudioBackend, devices: List<BackendDevice>): Pairing =
        devices.firstOrNull()?.let { runCatching { backend.capabilities(it).pairing }.getOrNull() }
            ?: Pairing.ANY_PAIR

    /** Non-null when the chosen input and output share no sample rate; the sentence to show. */
    private var ratesIncompatible: String? = null

    /** Non-null when the chosen device offers no workable buffer size; the sentence to show. */
    private var unusableDevice: String? = null

    /** Whatever currently prevents starting, or null when the selection is good. */
    private val blockingProblem: String? get() = ratesIncompatible ?: unusableDevice

    /** Reflects a mismatch (or its clearing) into Start and the status line after a device change. */
    private fun updateRateCompatibilityStatus() {
        val message = blockingProblem
        if (message != null) {
            startButton.isEnabled = false
            setStatus(message, Theme.BAD)
        } else if (!engine.isRunning) {
            startButton.isEnabled = inputCombo.itemCount > 0 && outputCombo.itemCount > 0
            val input = inputCombo.selectedItem as? BackendDevice
            val output = outputCombo.selectedItem as? BackendDevice
            if (input != null && output != null) {
                setStatus("${input.name} to ${output.name} at ${SampleRates.format(displayRate)}.", Theme.TEXT_DIM)
            }
        }
    }

    private fun mirrorSingleDeviceSelection() {
        if (!singleDevice) return
        val chosen = inputCombo.selectedItem as? BackendDevice ?: return
        populating = true
        outputCombo.model = DefaultComboBoxModel(arrayOf(chosen))
        outputCombo.selectedItem = chosen
        populating = false
        refreshRateAndBufferChoices()
    }

    /**
     * Repopulates RATE from what **both** endpoints accept, then BUFFER for whatever rate wins.
     *
     * The order matters and is the whole fix: buffer sizes are only meaningful for one rate, so the
     * rate has to be settled first. The bug this replaces read the rate from the input's
     * capabilities alone, so a 44.1 kHz-capable input paired with a 48 kHz-only HDMI output asked
     * the output for 44 100 and got a refusal — reported to the user as advice to change a Windows
     * setting that does not exist for that device.
     */
    private fun refreshRateAndBufferChoices() {
        val backend = selectedBackend ?: return
        val input = inputCombo.selectedItem as? BackendDevice
        val output = outputCombo.selectedItem as? BackendDevice
        val device = input ?: output ?: return

        val inputCaps = input?.let { runCatching { backend.capabilities(it) }.getOrNull() }
        val outputCaps = output?.let { runCatching { backend.capabilities(it) }.getOrNull() }

        // One driver owns both directions, so there is nothing to reconcile — asking the "output"
        // separately would just be the same device answering twice.
        val shared = if (singleDevice || output == null || input == null || input.id == output.id) {
            (inputCaps ?: outputCaps)?.sampleRates.orEmpty()
        } else {
            SampleRates.common(inputCaps?.sampleRates.orEmpty(), outputCaps?.sampleRates.orEmpty())
        }

        unusableDevice = null
        if (shared.isEmpty() && input != null && output != null) {
            ratesIncompatible = SampleRates.noOverlapMessage(
                input.name, inputCaps?.sampleRates.orEmpty(),
                output.name, outputCaps?.sampleRates.orEmpty(),
            )
            populating = true
            rateCombo.model = DefaultComboBoxModel()
            bufferCombo.model = DefaultComboBoxModel()
            populating = false
            return
        }
        ratesIncompatible = null

        val fixed = inputCaps?.fixedSampleRate ?: outputCaps?.fixedSampleRate
        val offered = if (fixed != null) listOf(fixed) else shared
        val previousRate = rateCombo.selectedItem as? Int
        val sameDevice = device.id == lastBufferDeviceId

        populating = true
        rateCombo.model = DefaultComboBoxModel(offered.toTypedArray())
        // The endpoints' own preference wins; it is the rate they are already set to, not a guess.
        rateCombo.selectedItem = when {
            sameDevice && previousRate != null && previousRate in offered -> previousRate
            else -> SampleRates.recommended(
                offered,
                inputCaps?.preferredSampleRate,
                outputCaps?.preferredSampleRate,
            )
        }
        populating = false
        rateCombo.isEnabled = fixed == null && offered.size > 1

        displayRate = (rateCombo.selectedItem as? Int) ?: fixed ?: 44_100
        refreshBufferChoices(backend, device, sameDevice)
        lastBufferDeviceId = device.id
    }

    /**
     * Buffer sizes for the rate now selected, defaulting to the driver's *preferred* size rather
     * than its minimum: a first run that clicks is worse than one a few milliseconds slower.
     */
    private fun refreshBufferChoices(backend: AudioBackend, device: BackendDevice, sameDevice: Boolean) {
        val caps = runCatching { backend.capabilities(device, displayRate) }.getOrNull() ?: return
        // A backend may find that an endpoint has no usable size at all — an HDMI output whose
        // driver rounds every buffer it is given, for instance. An empty picker plus a disabled
        // Start is the truthful outcome; offering a size that is certain to break up is not.
        unusableDevice = if (caps.unusableReason != null) {
            caps.unusableReason
        } else if (caps.bufferSizes.isEmpty()) {
            "${device.name} will not run in exclusive mode at ${SampleRates.format(displayRate)}. " +
                "Its driver does not honour any buffer size offered. Use a different device, or ASIO."
        } else {
            null
        }
        // Keep a deliberate choice across a rescan, but not across a change of device: 1024 frames
        // is legal on both a Realtek and a Sonicake and means something completely different on
        // each, so carrying it over silently hands the user the wrong default on the new interface.
        val previous = bufferCombo.selectedItem as? Int
        populating = true
        bufferCombo.model = DefaultComboBoxModel(caps.bufferSizes.toTypedArray())
        bufferCombo.selectedItem = when {
            sameDevice && previous != null && previous in caps.bufferSizes -> previous
            caps.preferredBufferFrames in caps.bufferSizes -> caps.preferredBufferFrames
            else -> caps.bufferSizes.lastOrNull()
        }
        populating = false
    }

    /**
     * True between pressing Start and the stream being up or having failed.
     *
     * The open runs on a worker thread now, so there is a window in which the engine is neither
     * stopped nor running and a second press would open a second stream on the same device. Under
     * exclusive mode that is a device left held, which on some drivers needs a reboot.
     */
    @Volatile
    private var starting = false

    private fun toggleEngine() {
        if (starting) return
        if (engine.isRunning) {
            stopEngine()
            setStatus("Stopped.", Theme.TEXT_DIM)
            return
        }

        val backend = selectedBackend
        val input = inputCombo.selectedItem as? BackendDevice
        val output = outputCombo.selectedItem as? BackendDevice
        if (backend == null || input == null || output == null) {
            setStatus("Select an input and an output device first.", Theme.BAD)
            return
        }
        blockingProblem?.let {
            setStatus(it, Theme.BAD)
            return
        }
        // The negotiated rate, not the input's own — that substitution was the bug.
        val rate = rateCombo.selectedItem as? Int ?: displayRate
        val caps = runCatching { backend.capabilities(input, rate) }.getOrNull()
        val frames = bufferCombo.selectedItem as? Int ?: caps?.preferredBufferFrames ?: 256

        // What was asked for, before the driver gets a say. Half of every "it won't open" report is
        // answered by comparing this line with the one after it.
        Log.info(
            "audio",
            "Opening ${backend.id.displayName}: \"${input.name}\" -> \"${output.name}\", " +
                "$rate Hz, $frames frames.",
        )

        val request = StreamRequest(
            input = input,
            inputChannel = 0,
            output = output,
            outputChannels = IntArray(minOf(2, maxOf(1, output.outputChannels))) { it },
            sampleRate = caps?.fixedSampleRate ?: rate,
            bufferFrames = frames,
        )

        // Off the event thread, and this is the whole reason: opening a stream is a quarter of a
        // second of driver calls and warm-up, during which the window was previously frozen — no
        // repaint, no progress, and long enough for Windows to consider dimming it. It also means
        // every line below that touches Swing has to come back through invokeLater.
        starting = true
        startButton.isEnabled = false
        setDeviceControlsEnabled(false)
        setStatus("Opening ${input.name}…", Theme.TEXT_DIM)

        Thread({
            try {
                engine.start(backend, request)
                SwingUtilities.invokeLater { streamStarted(input, output, rate, frames, caps) }
            } catch (t: Throwable) {
                SwingUtilities.invokeLater { streamFailedToStart(t) }
            }
        }, "omega-open-stream").apply { isDaemon = true }.start()
    }

    /** Back on the event thread, with the stream up. */
    private fun streamStarted(
        input: BackendDevice,
        output: BackendDevice,
        rate: Int,
        frames: Int,
        caps: DeviceCapabilities?,
    ) {
        starting = false
        startButton.isEnabled = true
        run {
            // The driver had the last word, as always: mirror whatever it actually granted.
            engine.format?.let {
                // The other half of the pair above. A driver quietly granting something else is a
                // real failure mode here — see the AORUS HDMI note in CLAUDE.md — and it stops being
                // visible the moment these numbers are mirrored back into the combos below.
                Log.info(
                    "audio",
                    "Driver granted ${it.bufferFrames} frames at ${it.sampleRate} Hz; " +
                        "reported round trip ${"%.2f".format(it.reportedRoundTripMs)} ms " +
                        "(in ${it.inputLatencyFrames}, out ${it.outputLatencyFrames}, " +
                        "adapter ${it.adapterLatencyFrames} frames).",
                )
                if (it.bufferFrames != frames || it.sampleRate != rate) {
                    Log.warn(
                        "audio",
                        "Driver did not honour the request — asked $frames frames at $rate Hz.",
                    )
                }
                displayRate = it.sampleRate
                populating = true
                if (it.sampleRate != rate && it.sampleRate in (0 until rateCombo.itemCount)
                        .map { i -> rateCombo.getItemAt(i) }
                ) {
                    rateCombo.selectedItem = it.sampleRate
                }
                if (it.bufferFrames != frames && it.bufferFrames in (caps?.bufferSizes ?: emptyList())) {
                    bufferCombo.selectedItem = it.bufferFrames
                }
                populating = false
            }
            // The engine zeroes its counters on start, so the baseline has to zero with them — or the
            // first snapshot of a second run compares against the first run and stays quiet.
            lastHealth = 0 to 0
            meterTimer.start()
            startButton.text = "Stop"
            startButton.lit = true
            recordButton.isEnabled = true
            // PLAY only becomes pressable now: before this there was no output path for a click.
            updateRhythmControls()
            // The driver may have granted a different rate or buffer than was asked for, and what it
            // granted is what should come back next time.
            saveSession()
            setStatus(
                "Running — ${input.name} to ${output.name} at ${SampleRates.format(displayRate)}",
                Theme.GOOD,
            )
            // Linux only in practice: Windows backends report nothing here. A thread that did not
            // get realtime priority crackles under load for a reason the user cannot see, and the
            // fix is an account setting — so say it now, while they are looking, not after.
            engine.schedulingNote?.let { note ->
                if (note.startsWith("realtime:")) {
                    Log.info("audio", "Scheduling: $note.")
                } else {
                    Log.warn("audio", "Scheduling: $note")
                    setStatus(
                        "Running, but the audio thread is not realtime and may crackle. " +
                            "Run with --list-backends for the fix.",
                        Theme.BAD,
                    )
                }
            }
            // Two captures at a small buffer is the one load this program knows in advance it may
            // not carry, so it is said at the moment the buffer is settled.
            announceSplitLoad()
        }
    }

    /** Back on the event thread, with nothing open. Puts every control back the way it was. */
    private fun streamFailedToStart(e: Throwable) {
        starting = false
        startButton.isEnabled = true
        setDeviceControlsEnabled(true)
        // With the trace: an open that fails inside a driver arrives as a JNA or COM error whose
        // message on its own is routinely just a hex code.
        Log.error("audio", "Could not open the stream.", e)
        setStatus("Could not open audio: ${e.message ?: e.javaClass.simpleName}", Theme.BAD)
    }

    /**
     * The stream's counters, as one line.
     *
     * `realtimeRatio` is deliberately first and printed to four places. It is the counter that
     * catches what the other two miss: a collection stalls the callback thread *between* callbacks,
     * so no callback body is ever slow, `deadlineMisses` stays at zero, and the driver simply gets
     * nothing. A log showing misses of 0 next to a ratio of 0.97 is that failure, and reading it the
     * other way round has sent people to tune the buffer size, which cannot help.
     */
    private fun logStreamHealth(prefix: String) {
        if (!Log.enabled) return
        Log.info(
            "audio",
            "$prefix — realtime ${"%.4f".format(engine.realtimeRatio)}, " +
                "xruns ${engine.xruns}, deadline misses ${engine.deadlineMisses}, " +
                "worst callback ${engine.worstCallbackMicros} us, " +
                "load ${"%.0f".format(engine.loadPercent * 100)} %.",
        )
    }

    /** Counters that have not moved since the last snapshot are not worth a line. See [meterTimer]. */
    private fun logStreamHealthIfChanged() {
        if (!Log.enabled || !engine.isRunning) return
        val state = engine.xruns to engine.deadlineMisses
        // The ratio drifts continuously, so it cannot take part in the comparison — but a stream
        // starving without ever missing a deadline moves neither counter, which is exactly the case
        // worth reporting. Hence the threshold as a second trigger rather than equality alone.
        val starving = engine.realtimeRatio < STARVING_BELOW
        if (state == lastHealth && !starving) return
        lastHealth = state
        logStreamHealth(if (starving) "Stream starving" else "Stream health")
    }

    private fun stopEngine() {
        // Before the stream goes, so the take is closed properly rather than by whatever happens to
        // notice afterwards.
        val wasRecording = recorder != null
        val finished = finishRecording()
        // The counters, while the stream still exists to be asked. This is the payoff for having a
        // log at all when the complaint is "it crackles": realtimeRatio is the one that catches a
        // collector pause, and deadlineMisses being zero beside a ratio under 1 is the signature.
        if (engine.isRunning) logStreamHealth("Stream stopping")
        engine.stop()
        // So the next run is not averaged against however long the application sat idle in between.
        cpu.reset()
        meterTimer.stop()
        meter.update(0f)
        transport.text = " "
        startButton.text = "Start"
        startButton.lit = false
        setDeviceControlsEnabled(true)
        recordButton.isEnabled = false
        // `engine.stop()` has already stopped the transport — the click cannot be heard without a
        // stream — so this is only the button and the lamps catching up.
        updateRhythmControls()
        beatLamps.clear()
        saveSession()
        if (wasRecording && finished != null) setStatus(finished, Theme.GOOD)
    }

    // ------------------------------------------------------------------ recording

    /**
     * Where takes go: the chosen folder, or `Desktop/Omega-Plugin`.
     *
     * Not the settings folder. Presets belong in hidden per-user config; a recording is a document
     * you go looking for, and one you cannot find is one you assume was never written.
     */
    private fun recordingFolder(): java.nio.file.Path {
        config.recordingFolder?.let { chosen ->
            runCatching { java.nio.file.Path.of(chosen) }.getOrNull()?.let { return it }
        }
        return defaultRecordingFolder()
    }

    /**
     * Where takes go when nobody has said otherwise: the Desktop on Windows, `$HOME` elsewhere.
     *
     * The split is not cosmetic. On Windows every account has a Desktop, it is where a document you
     * are going to go looking for belongs, and the file system's idea of "home" already *is* the
     * Desktop — including a OneDrive redirection that `user.home` knows nothing about, which is
     * exactly the case on the machine this was written on.
     *
     * On Linux none of that holds. A desktop folder is a convention of a desktop environment rather
     * than of the system: a headless box or a bare window manager has none, and a localised install
     * calls it `Escritorio` or `Skrivbord`. Creating an English `~/Desktop` that nothing else on the
     * machine uses would be inventing a folder to satisfy an assumption, so the home directory is
     * the honest answer. Anything else is one menu item away and is remembered.
     */
    private fun defaultRecordingFolder(): java.nio.file.Path {
        val home = java.nio.file.Path.of(System.getProperty("user.home"))
        if (!System.getProperty("os.name").orEmpty().lowercase().contains("win")) {
            return home.resolve("Omega-Plugin")
        }
        val reported = runCatching {
            javax.swing.filechooser.FileSystemView.getFileSystemView().homeDirectory
        }.getOrNull()
        val desktop = if (reported != null && reported.isDirectory &&
            reported.name.equals("Desktop", ignoreCase = true)
        ) {
            reported.toPath()
        } else {
            home.resolve("Desktop")
        }
        return desktop.resolve("Omega-Plugin")
    }

    private fun toggleRecording() {
        if (recorder != null) {
            finishRecording()?.let { setStatus(it, Theme.GOOD) }
            return
        }
        // Pressed again while the count-in is still running: abandon the take rather than stack a
        // second one behind it. The files are removed, because a zero-length WAV in the recordings
        // folder is litter that looks like a failure.
        if (armedForCountIn) {
            cancelArmedRecording()
            setStatus("Count-in cancelled.", Theme.TEXT_DIM)
            return
        }
        if (!engine.isRunning) {
            setStatus("Start the stream before recording.", Theme.BAD)
            return
        }

        val folder = recordingFolder()
        val stamp = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss"))
        // Named after the preset as well as the time, because "which of these is the one with the
        // GOAT capture" is a question you will ask.
        val patch = loaded?.name?.let { " $it" }.orEmpty()
        val target = folder.resolve("$stamp$patch.wav").toFile()
        val twinTarget = folder.resolve("$stamp$patch$TWIN_SUFFIX.wav").toFile()

        try {
            val started = Recorder.start(target, displayRate)
            // Opened here, on the event thread, whether or not it starts on the beat: creating the
            // file is the part with a disk behind it and the audio thread must never do it.
            val twin = if (recordTwin) Recorder.start(twinTarget, displayRate) else null

            // Only when the metronome is already running. `armForRecord` refuses otherwise and
            // returns false, and Record then does what Record has always done — opens the file now.
            // **Pressing Record never starts the metronome.**
            if (countInEnabled && rhythm.armForRecord(countInBars)) {
                engine.pendingRecorder = started
                engine.pendingRhythmRecorder = twin
                armedForCountIn = true
                setStatus(
                    if (countInBars == 1) "Waiting for the downbeat…"
                    else "Counting in — $countInBars bars…",
                    Theme.TEXT_DIM,
                )
            } else {
                engine.recorder = started
                engine.rhythmRecorder = twin
                setStatus("Recording to ${target.name} in $folder", Theme.GOOD)
            }
            recorder = started
            rhythmRecorder = twin
            rhythmPlayedDuringTake = rhythm.isRunning
            recordButton.lit = true
            updateRhythmControls()
        } catch (e: Exception) {
            Log.error("record", "Could not start recording into $folder.", e)
            setStatus(
                "Could not record into $folder: ${e.message ?: e.javaClass.simpleName}",
                Theme.BAD,
            )
        }
    }

    /**
     * Notices that the count-in finished and the take is actually running.
     *
     * The handover itself happened on the audio thread, on the beat. This only catches up with it —
     * the button and the status line are 30 Hz things, and a third of a frame of lag on a label is
     * not worth a callback to the event thread from the realtime path.
     */
    private fun pollCountIn() {
        if (!armedForCountIn) return
        if (engine.pendingRecorder != null) {
            countInStatus()
            return
        }
        armedForCountIn = false
        // The count-in is not in the file — recording starts on the downbeat *after* it — so the
        // clicks that just played must not count as "the metronome played during this take". Left
        // set, a count-in on its own would keep a " - drums" copy that is byte-for-byte the dry one.
        rhythmPlayedDuringTake = rhythm.isRunning
        recorder?.let { setStatus("Recording to ${it.file.name}", Theme.GOOD) }
        updateRhythmControls()
    }

    /** Counts the bars down in the status line, so a two-bar count-in does not look like a hang. */
    private fun countInStatus() {
        val left = rhythm.countInBarsRemaining
        val text = when {
            left <= 1 -> "Waiting for the downbeat…"
            else -> "Counting in — $left bars…"
        }
        if (status.text != text) setStatus(text, Theme.TEXT_DIM)
    }

    /** Drops a take that never started, and takes its empty files with it. */
    private fun cancelArmedRecording() {
        rhythm.disarm()
        engine.pendingRecorder = null
        engine.pendingRhythmRecorder = null
        armedForCountIn = false
        listOfNotNull(recorder, rhythmRecorder).forEach { open ->
            runCatching { open.stop() }
            runCatching { open.file.delete() }
        }
        recorder = null
        rhythmRecorder = null
        recordButton.lit = false
        updateRhythmControls()
    }

    /** Closes the take. Returns what to tell the user, or null if nothing was recording. */
    private fun finishRecording(): String? {
        if (armedForCountIn) {
            // Stopped before the count-in ever handed the files over. Nothing was written.
            cancelArmedRecording()
            return null
        }
        val active = recorder ?: return null
        val twin = rhythmRecorder
        recorder = null
        rhythmRecorder = null
        // Cleared before stopping: a callback already holding the old reference writes into a ring
        // nobody will drain, which is harmless, whereas one writing into a closed file is not.
        engine.recorder = null
        engine.rhythmRecorder = null
        val seconds = active.elapsedSeconds
        active.stop()
        twin?.stop()
        recordButton.lit = false
        updateRhythmControls()

        // A twin recorded while the metronome never played is byte-for-byte the dry take. Keeping it
        // would leave two identical files with confusingly different names in the folder every time
        // the toggle was left on and the transport was not started.
        val twinKept = twin != null && rhythmPlayedDuringTake
        if (twin != null && !twinKept) runCatching { twin.file.delete() }
        rhythmPlayedDuringTake = false

        val problems = buildString {
            active.failure?.let { append("  Stopped early: $it.") }
            if (active.droppedFrames > 0) {
                append(
                    String.format(
                        "  Lost %.0f ms — the disk could not keep up.",
                        active.droppedFrames * 1000f / displayRate,
                    )
                )
            }
            // Reported only when it differs from the dry take's: the twin shares a disk and a rate
            // with it, so repeating the same complaint twice would be noise rather than detail.
            if (twinKept) {
                twin.failure?.takeIf { it != active.failure }?.let { append("  The drums copy stopped early: $it.") }
                if (twin.droppedFrames > 0 && twin.droppedFrames != active.droppedFrames) {
                    append(String.format("  The drums copy lost %.0f ms.", twin.droppedFrames * 1000f / displayRate))
                }
            }
        }
        return String.format(
            "Recorded %.0f s to %s%s%s",
            seconds,
            active.file.absolutePath,
            if (twinKept) " (and $TWIN_SUFFIX)" else "",
            problems,
        )
    }

    /**
     * Picks the recording folder, in a chooser dressed to match the window.
     *
     * `FileDialog` — the platform's own, used for every other picker here — cannot select a folder
     * on Windows: its API has only `LOAD` and `SAVE`, and the one escape hatch is honoured on macOS
     * alone. So this is Swing's chooser, taken out of Nimbus's hands by [DarkChrome] and shown in an
     * undecorated dialog of our own, like every other dialog in this application. A native folder
     * picker through COM would be better still and is not written.
     */
    private fun chooseRecordingFolder() {
        val chooser = javax.swing.JFileChooser(recordingFolder().toFile()).apply {
            fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
            approveButtonText = "Use folder"
            background = Theme.PANEL
            // Without a filter the type row is an empty bar the chooser will not let us remove.
            // Naming it costs a line and turns a blank control into a statement of what is on offer.
            fileFilter = object : javax.swing.filechooser.FileFilter() {
                override fun accept(f: java.io.File) = f.isDirectory
                override fun getDescription() = "Folders"
            }
        }
        DarkChrome.apply(chooser)

        val dialog = javax.swing.JDialog(
            this,
            "Select recording destination",
            java.awt.Dialog.ModalityType.APPLICATION_MODAL,
        ).apply {
            isUndecorated = true
            isResizable = false
        }

        var chosen: java.io.File? = null
        // Used standalone rather than through showDialog, so its buttons arrive here as actions.
        chooser.addActionListener { event ->
            if (event.actionCommand == javax.swing.JFileChooser.APPROVE_SELECTION) {
                chosen = chooser.selectedFile
            }
            dialog.dispose()
        }
        dialog.rootPane.registerKeyboardAction(
            { dialog.dispose() },
            javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW,
        )

        dialog.contentPane.add(
            JPanel(BorderLayout(0, 12)).apply {
                background = Theme.PANEL
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Theme.TRACK, 1),
                    BorderFactory.createEmptyBorder(18, 20, 16, 20),
                )
                // The dialog has no frame of its own, so the heading is the title.
                add(
                    JLabel("Select recording destination").apply {
                        font = Theme.TITLE_FONT
                        foreground = Theme.TEXT
                    },
                    BorderLayout.NORTH,
                )
                add(chooser, BorderLayout.CENTER)
            }
        )
        dialog.pack()
        dialog.setLocationRelativeTo(this)
        dialog.isVisible = true

        val folder = chosen ?: return
        config.recordingFolder = folder.absolutePath
        setStatus("Recordings will be written to ${folder.absolutePath}", Theme.GOOD)
    }

    private fun openRecordingFolder() {
        val folder = recordingFolder()
        try {
            java.nio.file.Files.createDirectories(folder)
        } catch (e: Exception) {
            setStatus("Could not create $folder: ${e.message ?: e.javaClass.simpleName}", Theme.BAD)
            return
        }
        setStatus("Recordings are in $folder", Theme.TEXT_DIM)
        // Off the event thread: Desktop.open blocks until the file manager has started, which on a
        // cold cache is long enough to freeze the window.
        Thread({ runCatching { Desktop.getDesktop().open(folder.toFile()) } }, "omega-open-recordings")
            .apply { isDaemon = true }
            .start()
    }

    /** Full models, parked while the stream runs so [setDeviceControlsEnabled] can put them back. */
    private var parkedModels: List<Pair<JComboBox<*>, javax.swing.ComboBoxModel<*>>> = emptyList()

    /**
     * Each combo's own preferred size, or null where it had none, across the model swap.
     *
     * A `JComboBox` measures itself against **every item in its model**, not against the selected
     * one. Collapsing the model to a single entry therefore shrinks the control to whatever that one
     * value happens to need, and since the whole bar is laid out left to right, everything after it
     * slides. Pressing Start rearranged the top of the window.
     *
     * `inputCombo` and `outputCombo` never showed it because they are pinned to 190 px for an
     * unrelated reason — a device name would otherwise set the width of the window. The other three
     * had nothing holding them still.
     */
    private var parkedSizes: List<Pair<JComboBox<*>, Dimension?>> = emptyList()

    /**
     * Locks the device choices for the life of a stream by collapsing each list to its current
     * value, rather than by disabling the combos.
     *
     * `isEnabled = false` is the obvious way and it loses the information: Nimbus paints a disabled
     * combo's value through its own `SynthStyle`, so all four pickers go visibly blank the moment
     * Start is pressed — exactly when the user most wants to read which driver, device and buffer
     * are live. Neither a `UIManager` override nor a cell renderer gets in front of that. A
     * one-entry enabled combo is just as unchangeable, and it still says what it is set to.
     */
    private fun setDeviceControlsEnabled(enabled: Boolean) {
        val combos = listOf(backendCombo, inputCombo, outputCombo, rateCombo, bufferCombo)
        populating = true
        if (!enabled) {
            if (parkedModels.isEmpty()) {
                parkedModels = combos.map { combo -> combo to combo.model }
                // Read first, then write the same value back: reading returns what the *full* model
                // computes, and writing it makes that survive the collapse below. Must happen before
                // the model is replaced, or the width being frozen is already the narrow one.
                parkedSizes = combos.map { combo ->
                    val own = if (combo.isPreferredSizeSet) combo.preferredSize else null
                    val measured = combo.preferredSize
                    combo.preferredSize = Dimension(measured.width, measured.height)
                    combo to own
                }
                for (combo in combos) {
                    val current = combo.selectedItem
                    @Suppress("UNCHECKED_CAST")
                    (combo as JComboBox<Any>).model =
                        DefaultComboBoxModel(if (current == null) emptyArray() else arrayOf(current))
                }
            }
        } else if (parkedModels.isNotEmpty()) {
            for ((combo, model) in parkedModels) {
                @Suppress("UNCHECKED_CAST")
                (combo as JComboBox<Any>).model = model as javax.swing.ComboBoxModel<Any>
            }
            parkedModels = emptyList()
            // Null hands sizing back to the model, which is what the three unpinned combos want now
            // that their full lists are back.
            for ((combo, own) in parkedSizes) combo.preferredSize = own
            parkedSizes = emptyList()
        }
        populating = false

        refreshButton.isEnabled = enabled
        // Nor can the chain order — delay lines and reverb tails would be carried into a new
        // position mid-stream, which is audible. Both boards: the second is in the engine whether
        // or not it is heard.
        board.view.locked = !enabled
        board2.view.locked = !enabled
    }

    // ------------------------------------------------------------------ session

    /**
     * Notes that something worth remembering has changed, without writing anything yet.
     *
     * Called from every interaction that a session describes — a knob, a switch, a drag, a device
     * choice, a capture being loaded — because the alternative is a list of places that have to
     * remember to opt in, and the one that forgets is the one you find out about a month later.
     */
    private fun touchSession() {
        if (restoring) return
        sessionSaver.restart()
    }

    /** Writes the session now, cancelling any pending coalesced write. */
    private fun saveSession() {
        sessionSaver.stop()
        if (restoring) return
        // A settings file that cannot be written is not worth interrupting anyone over, and this one
        // runs on the way out of the application, where an exception has nowhere useful to go.
        runCatching { config.session = currentSession().toJsonValue() }
    }

    /**
     * The window as it stands, in the shape a session stores.
     *
     * The sound is captured whether or not a preset is loaded; the preset's name rides along in the
     * captured sound's own name, and is blank when there is none.
     */
    private fun currentSession() = Session(
        sound = captureSound(loaded?.name.orEmpty()),
        edited = edited,
        backend = selectedBackend?.id?.name,
        input = deviceRef(inputCombo.selectedItem),
        output = deviceRef(outputCombo.selectedItem),
        sampleRate = rateCombo.selectedItem as? Int,
        bufferFrames = bufferCombo.selectedItem as? Int,
        rhythm = currentRhythm(),
    )

    /**
     * The rhythm row as it stands.
     *
     * Whether the transport is *playing* is deliberately not stored. Restoring a session should put
     * the controls back, not start making a noise before the user has pressed anything — and it
     * could not anyway, since the stream is not open when a session is read.
     */
    private fun currentRhythm(): Session.Rhythm {
        val clock = rhythm
        return Session.Rhythm(
            tempoBpm = clock.tempoBpm,
            signature = clock.signature.display,
            feel = (feelCombo.selectedItem as? Pattern)?.name ?: clock.pattern.name,
            level = clock.level,
            clickOverDrums = clock.clickOverDrums,
            recordTwin = recordTwin,
            countIn = countInEnabled,
            countInBars = countInBars,
        )
    }

    /**
     * Puts the rhythm row back, tolerating anything the file happens to say.
     *
     * A signature or a feel that no longer exists falls through to the default rather than failing
     * the restore: the catalogue is expected to grow and to be renamed, and a session written by a
     * later version has to remain readable by this one.
     */
    private fun restoreRhythm(saved: Session.Rhythm?) {
        if (saved == null) return
        val signature = TimeSignature.entries.firstOrNull { it.display == saved.signature }
            ?: TimeSignature.FOUR_FOUR

        val was = populating
        populating = true
        try {
            rhythm.tempoBpm = saved.tempoBpm.coerceIn(Transport.MIN_BPM, Transport.MAX_BPM)
            showTempo(rhythm.tempoBpm)
            rhythm.signature = signature
            signatureCombo.selectedItem = signature
            beatLamps.beats = signature.beatsPerBar
            rebuildFeels(signature, select = saved.feel)
            rhythmLevelParameter.value = saved.level.coerceIn(0f, 1f)
            rhythm.level = rhythmLevelParameter.value
            rhythmLevel.repaint()
            rhythm.clickOverDrums = saved.clickOverDrums
            recordTwin = saved.recordTwin
            countInEnabled = saved.countIn
            countInBars = saved.countInBars.coerceIn(1, 2)
        } finally {
            populating = was
        }
    }

    private fun deviceRef(selected: Any?): Session.DeviceRef? =
        (selected as? BackendDevice)?.let { Session.DeviceRef(it.id, it.name) }

    /**
     * Puts back the sound, the order, and what each module had loaded.
     *
     * Not routed through [load]: that path is the one for *choosing* a preset, so it asks before
     * discarding edits, clears the dirty flag and announces itself. A session is not being chosen —
     * it is what was already on screen — so it must do none of those. In particular the • dot comes
     * back, because a preset that was modified and not saved has to return modified.
     *
     * The whole of it is guarded, and deliberately so. A session that cannot be restored must cost
     * the session and nothing else; an application that will not open because of a remembered
     * preference is a far worse failure than one that opens on defaults.
     */
    private fun restoreSound(session: Session) {
        restoring = true
        try {
            // With the stream down, so a capture is never loaded into a running callback and its
            // warm-up costs nothing anyone hears.
            val missing = applySound(session.sound)

            // The name is only claimed back if the preset is still on disk. Otherwise "Save" would
            // quietly recreate a preset that was deleted between runs — while the sound itself, which
            // is what actually matters, is restored either way.
            val matched = session.presetName?.let { name ->
                presets.firstOrNull { it.name.equals(name, ignoreCase = true) }
            }
            loaded = matched
            edited = session.edited || (session.presetName != null && matched == null)
            updateTitle()

            if (missing.isNotEmpty()) setStatus(missing.joinToString("  "), Theme.BAD)
        } catch (e: Throwable) {
            setStatus(
                "Your last session could not be restored: ${e.message ?: e.javaClass.simpleName}",
                Theme.BAD,
            )
        } finally {
            restoring = false
        }
    }

    /**
     * Puts back the driver, devices, rate and buffer, and names anything that has gone.
     *
     * Order is the whole of the difficulty. The combos feed each other — the legal rates depend on
     * both endpoints, and the legal buffer sizes depend on the rate — so each step is applied under
     * the [populating] guard and the cascade is then run by hand. A saved value that is no longer
     * offered is dropped rather than forced, which leaves the driver's own recommendation in place.
     */
    private fun restoreDevices(session: Session) {
        val backend = selectedBackend ?: return
        if (!backend.availability.isAvailable) return

        // A different backend from the one saved means the saved device ids belong to a driver that
        // is not in use, so there is nothing here worth matching against.
        val wantedBackend = session.backend
        if (wantedBackend != null && backend.id.name != wantedBackend) {
            val absent = Backends.all().firstOrNull { it.id.name == wantedBackend }
            val reason = absent?.availability?.reason?.let { " $it" }.orEmpty()
            setStatus(
                "${absent?.id?.displayName ?: "The driver you were using"} is not available — " +
                    "using ${backend.id.displayName}.$reason",
                Theme.BAD,
            )
            return
        }

        val replaced = mutableListOf<String>()

        selectSilently(inputCombo, matchDevice(inputCombo, session.input)) {
            session.input?.let { wanted -> replaced += wanted.name }
        }

        if (singleDevice) {
            // One driver owns both directions, so the output follows the input and choosing it
            // separately would be a fiction.
            mirrorSingleDeviceSelection()
        } else {
            selectSilently(outputCombo, matchDevice(outputCombo, session.output)) {
                session.output?.let { wanted -> replaced += wanted.name }
            }
            refreshRateAndBufferChoices()
        }

        // RATE before BUFFER: a frame count only means anything at one rate, and changing the rate
        // rebuilds the buffer list underneath it.
        val rate = session.sampleRate
        if (rate != null && rate in itemsOf(rateCombo)) {
            populating = true
            rateCombo.selectedItem = rate
            populating = false
            displayRate = rate
            ((inputCombo.selectedItem ?: outputCombo.selectedItem) as? BackendDevice)?.let {
                refreshBufferChoices(backend, it, sameDevice = true)
            }
        }

        val frames = session.bufferFrames
        if (frames != null && frames in itemsOf(bufferCombo)) {
            populating = true
            bufferCombo.selectedItem = frames
            populating = false
        }

        updateRateCompatibilityStatus()

        if (replaced.isNotEmpty()) {
            setStatus(
                "${replaced.joinToString(" and ")} " +
                    (if (replaced.size == 1) "is" else "are") +
                    " not available — using " +
                    listOfNotNull(
                        (inputCombo.selectedItem as? BackendDevice)?.name,
                        (outputCombo.selectedItem as? BackendDevice)?.name.takeIf { !singleDevice },
                    ).distinct().joinToString(" and ") +
                    " on ${backend.id.displayName}.",
                Theme.BAD,
            )
        }
    }

    /**
     * Selects [device] without waking the combo's listener, or runs [absent] when it is not there.
     *
     * The listener has to stay asleep because the cascade it drives is being run deliberately, in
     * order, by the caller — letting it fire here would rebuild the rate and buffer lists in the
     * middle of restoring them.
     */
    private fun selectSilently(
        combo: JComboBox<BackendDevice>,
        device: BackendDevice?,
        absent: () -> Unit,
    ) {
        if (device == null) {
            // Only worth reporting if there was something to fall back *from*: an empty list is
            // already being explained by refreshDevices.
            if (combo.itemCount > 0) absent()
            return
        }
        populating = true
        combo.selectedItem = device
        populating = false
    }

    /**
     * The saved device, by its driver id and then by its name.
     *
     * The id is the contract — it is meant to survive a disconnect, a reconnect and a restart — but
     * a driver update can still change one, and in that case the name is a better guess than giving
     * up. Null means it is genuinely not here, and the first device in the list stays selected.
     */
    private fun matchDevice(
        combo: JComboBox<BackendDevice>,
        wanted: Session.DeviceRef?,
    ): BackendDevice? {
        if (wanted == null) return null
        val devices = itemsOf(combo)
        return devices.firstOrNull { it.id == wanted.id }
            ?: devices.firstOrNull { it.name == wanted.name }
    }

    private fun <T> itemsOf(combo: JComboBox<T>): List<T> =
        (0 until combo.itemCount).mapNotNull { combo.getItemAt(it) }

    private fun setStatus(text: String, color: java.awt.Color) {
        // A new message takes over from a "Copied" acknowledgement rather than being overwritten by
        // it a moment later.
        copiedTimer?.stop()
        copiedTimer = null
        statusText = text
        statusColor = color
        status.text = text
        status.foreground = color
        status.toolTipText = if (text.isBlank()) null else "Click to copy"
    }

    /** True when the pointer is over the drawn text rather than the empty rest of the label. */
    private fun overStatusText(event: MouseEvent): Boolean {
        val text = status.text
        if (text.isNullOrBlank()) return false
        val width = status.getFontMetrics(status.font).stringWidth(text)
        return event.x <= width + 4
    }

    /**
     * Puts the current message on the system clipboard and says so, briefly.
     *
     * The acknowledgement replaces the message for a moment and then puts it back, because a status
     * line that silently does something is indistinguishable from one that is broken.
     */
    private fun copyStatus() {
        val text = statusText
        if (text.isBlank()) return

        val copied = try {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard
                .setContents(StringSelection(text), null)
            true
        } catch (_: IllegalStateException) {
            // The clipboard belongs to the OS and another application can hold it. Not our bug, and
            // not worth an exception — but the user still needs to know the click did nothing.
            false
        }

        copiedTimer?.stop()
        status.text = if (copied) "Copied to clipboard." else "The clipboard is busy — try again."
        status.foreground = if (copied) Theme.GOOD else Theme.BAD
        copiedTimer = javax.swing.Timer(1_400) {
            status.text = statusText
            status.foreground = statusColor
            copiedTimer = null
        }.apply {
            isRepeats = false
            start()
        }
    }

    private companion object {
        /**
         * How long the session waits after the last change before it is written.
         *
         * Long enough that a knob being swept, or a pedal being dragged across the board, is one
         * write rather than hundreds. Short enough that anything you would actually mind losing has
         * been on disk for a while by the time you notice.
         */
        const val SESSION_SAVE_DELAY_MS = 10_000

        /** ~60 s of 33 ms meter ticks. See the health snapshot in `meterTimer`. */
        const val HEALTH_TICKS = 1800

        /** Appended to a take's name for the copy carrying the click and the kit. */
        const val TWIN_SUFFIX = " - drums"

        /**
         * The smallest buffer two amp captures are expected to hold.
         *
         * One capture measured at 97.7 % of a 64-frame period, so two will not fit at 64 and are on
         * the line at 128. Said rather than enforced: the machine may be faster than the one that
         * was measured, and `realtimeRatio` will tell the truth either way.
         */
        const val SPLIT_NAM_FRAMES = 256

        /**
         * How many taps the tempo is averaged over.
         *
         * Four covers a bar of 4/4, which is how people actually tap, and averaging over the run
         * rather than the last gap halves the timing error a single tap contributes.
         */
        const val TAP_WINDOW = 5

        /** A gap longer than this is somebody returning to the button, not a slow pulse. */
        const val TAP_TIMEOUT_MS = 2_500L

        /**
         * Below this the device was starved and the user heard it.
         *
         * The same 0.995 named in `AudioStream.realtimeRatio`: measured on this machine at a
         * 64-frame ASIO buffer under allocation pressure, G1 sat at 0.9885 and generational ZGC at
         * 0.9999, so the line falls between the two rather than being a round number.
         */
        const val STARVING_BELOW = 0.995f
    }
}
