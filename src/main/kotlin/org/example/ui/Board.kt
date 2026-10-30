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

import org.example.audio.ImpulseLoader
import org.example.audio.SampleRates
import org.example.dsp.AudioProcessor
import org.example.dsp.BoardLevel
import org.example.dsp.Compressor
import org.example.dsp.Delay
import org.example.dsp.EffectChain
import org.example.dsp.Eq
import org.example.dsp.FxSlot
import org.example.dsp.Gain
import org.example.dsp.Gate
import org.example.dsp.Ir
import org.example.dsp.Nam
import org.example.dsp.Overdrive
import org.example.dsp.Reverb
import org.example.dsp.TypedModule
import org.example.dsp.nam.NamFile
import org.example.preset.Preset
import org.example.util.Log
import java.awt.Color
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Files
import javax.swing.JPopupMenu

/**
 * One pedal board: its effects, the chain that runs them, the view that shows them, and everything
 * that belongs to exactly one of them — which file its NAM has loaded, which of its FX slots is
 * first, where its IR chooser should open.
 *
 * Extracted from `MainWindow` so that it can exist **twice**. Split mode is a second board fed the
 * same guitar and summed with the first, and every rule a board follows — membership changes only
 * with the stream stopped, slots are numbered by position, a missing file costs the file and nothing
 * else — holds for each independently. What stays in the window is what there is one of: the
 * master, the devices, the session, the presets.
 *
 * The chain ends in [level], the board's own LEVEL. It is always in the chain, because the chain's
 * membership cannot change while the stream runs, and it is inactive — at unity, and skipping the
 * buffer — until split mode makes it mean something.
 */
class Board(private val owner: Frame, private val host: Host) {

    /** What a board needs from the window, and nothing more. */
    interface Host {
        fun status(text: String, colour: Color)
        fun markEdited()

        /** True if the stream is stopped; otherwise says why the change was refused and returns false. */
        fun requireStoppedForBoardChange(): Boolean

        /** The sample rate the device bar is showing, for sizing a capture's cost and latency. */
        val displayRate: Int

        /** After a capture arrives or leaves, so the window can reconsider what both boards cost. */
        fun ampChanged()
    }

    val gate = Gate()
    val compressor = Compressor()
    val eq = Eq()
    val gain = Gain()
    val overdrive = Overdrive()
    val nam = Nam()
    val ir = Ir()
    val fx1 = FxSlot()
    val delay = Delay()
    val reverb = Reverb()

    /** The board's output level in split mode. Pinned after the last pedal; never on the board. */
    val level = BoardLevel()

    // The default order is the conventional one. Gate first, because gating after a distortion means
    // gating the noise the distortion just amplified. The cabinet sits after the drive and before
    // the time effects, so delay and reverb are a room around the speaker rather than through it.
    //
    // The EQ sits before the drive, because an EQ in front of a clipper shapes what the clipper is
    // given and an EQ behind it only tidies up afterwards — and on a clean sound, which is where
    // this one earns its place, the position makes no difference at all.
    //
    // FX 1 lands after the cabinet and before the time effects, which is the post slot on any
    // modelling pedal and the useful default for what tends to go in it. It is only a default: like
    // every other pedal it can be dragged, and unlike them there can be more than one of it.
    private val pedals = listOf(gate, compressor, eq, gain, overdrive, nam, ir, fx1, delay, reverb)

    val chain = EffectChain(pedals + level)

    val view = ChainBoard(pedals)

    /** The IR pedal's load button, kept so its label can show the cabinet in use. */
    private var irButton: EffectPanel.HeaderButton? = null

    /** The NAM pedal's load button, likewise. */
    private var namButton: EffectPanel.HeaderButton? = null

    /** The NAM pedal's overflow menu. Dead until a capture is loaded — it has nothing to say before. */
    private var namMenu: EffectPanel.MenuButton? = null

    /** The ✕ on each file-loading module. Present only while there is a file to discard. */
    private var irClear: EffectPanel.ClearButton? = null
    private var namClear: EffectPanel.ClearButton? = null

    /**
     * The `+` and `✕` on each FX slot, kept so the ✕ can be hidden on whichever slot is currently
     * first — FX 1 is permanent, and which slot *is* FX 1 changes as the board is rearranged.
     */
    private val slotButtons = HashMap<FxSlot, Pair<EffectPanel.IconButton, EffectPanel.ClearButton>>()

    /**
     * Where each module's loaded file came from, keyed by effect name, so a preset can record it.
     *
     * Alongside it, where a preset's file *should* have been when it was not there — kept so the
     * chooser can open where the file used to live, which is usually one folder from where it went.
     */
    private val loadedFiles = HashMap<String, String>()
    private val missingFiles = HashMap<String, String>()

    init {
        // The level stays pinned after the last pedal; only the pedals move.
        view.onReorder = { order ->
            chain.reorder(order + level)
            // Slots are numbered by position, so dragging one past another renames both.
            renumberSlots()
            host.markEdited()
        }
        view.onEdited = { host.markEdited() }

        // The only module that needs more than knobs. The button lives on its own pedal rather than
        // in the menu, because that is where you are looking when you want to change cabinet.
        irButton = view.panelFor(ir)?.addHeaderButton("LOAD", "Load an impulse response") {
            chooseImpulse()
        }
        namButton = view.panelFor(nam)?.addHeaderButton("LOAD", "Load a Neural Amp Modeler capture") {
            chooseNamModel()
        }
        namMenu = view.panelFor(nam)?.addHeaderMenu("More NAM settings") { popup -> buildNamMenu(popup) }
        namMenu?.isEnabled = false

        // Added last so they land left of LOAD — see EffectPanel.addHeaderClear.
        irClear = view.panelFor(ir)?.addHeaderClear("Remove this cabinet") { clearIr() }
        namClear = view.panelFor(nam)?.addHeaderClear("Remove this amp") { clearNam() }

        view.panelFor(fx1)?.let { wireSlot(it, fx1) }
        renumberSlots()
    }

    /** The pedals in signal order. */
    val order: List<AudioProcessor> get() = view.order

    /** Everything on this board in the order the chain runs it: the pedals, then the level. */
    fun allEffects(): List<AudioProcessor> = view.order + level

    // ------------------------------------------------------------- FX slots

    /** The slots on the board, in signal order — which is also their numbering order. */
    private fun slots(): List<FxSlot> = view.order.filterIsInstance<FxSlot>()

    /**
     * Gives a slot's pedal its `+` and `✕`.
     *
     * Added in this order because both insert at the far left of the header, so the *last* one added
     * ends up leftmost: `✕`, then `+`, then the type dropdown the panel built for itself. Discard on
     * the outside, where it has to be aimed at rather than passed over on the way to something else.
     */
    private fun wireSlot(panel: EffectPanel, slot: FxSlot) {
        val add = panel.addHeaderIcon(
            EffectPanel.IconButton.Kind.ADD,
            "Add another FX module after this one",
        ) { addSlotAfter(slot) }
        val remove = panel.addHeaderClear("Remove this FX module") { deleteSlot(slot) }
        slotButtons[slot] = add to remove
    }

    /**
     * Renumbers every slot from its position, and hides the first one's `✕`.
     *
     * Run after anything that can change the board's membership or order. The numbers are labels
     * computed from position, never identities — see [FxSlot.label] — so this is allowed to
     * renumber freely, and a preset written afterwards agrees with the board because it is captured
     * from the board rather than from anything remembered across the change.
     */
    private fun renumberSlots() {
        val slots = slots()
        slots.forEachIndexed { index, slot ->
            slot.label = FxSlot.labelFor(index + 1)
            // The first slot is permanent. Not disabled — absent: a greyed ✕ that can never be
            // pressed is a control whose whole job is to explain that it does nothing.
            slotButtons[slot]?.second?.isVisible = index > 0
        }
        view.refreshTitles()
    }

    /**
     * Adds a slot immediately after [after], with the stream stopped.
     *
     * Refused rather than queued while playing, and it is the same rule the drag handle follows:
     * membership changes mean publishing an effect that has not been prepared or warmed, and the
     * warm-up is the thing standing between the first second of a new module and an xrun.
     */
    private fun addSlotAfter(after: FxSlot) {
        if (!host.requireStoppedForBoardChange()) return
        val slot = FxSlot()
        wireSlot(view.addPanel(slot, after), slot)
        renumberSlots()
        chain.replaceAll(allEffects())
        host.markEdited()
        host.status("Added ${slot.label}.", Theme.TEXT_DIM)
    }

    /**
     * Removes a slot, asking first if there is anything in it to lose.
     *
     * A slot added a moment ago and never touched goes without a dialog; one that has been dialled
     * in asks. The confirmation is about the work, not about the click.
     */
    private fun deleteSlot(slot: FxSlot) {
        if (!host.requireStoppedForBoardChange()) return
        if (!slot.isUntouched()) {
            val confirmed = ConfirmDialog.ask(
                owner = owner,
                title = "Remove ${slot.label}",
                message = "Remove ${slot.label}?",
                detail = "It is set to ${slot.typeName}. Its settings will be lost.",
                confirmText = "Remove",
                confirmColour = Theme.BAD,
            )
            if (!confirmed) return
        }
        val name = slot.label
        slotButtons.remove(slot)
        view.removePanel(slot)
        renumberSlots()
        chain.replaceAll(allEffects())
        host.markEdited()
        host.status("Removed $name.", Theme.TEXT_DIM)
    }

    /**
     * Makes the board carry exactly the slots a sound describes, then fills them in.
     *
     * This is the step [Preset.applyTo] cannot do. That method matches modules by name and applies
     * what it finds; it has no way to bring a module into existence, and a preset with three slots
     * loaded onto a board with one would otherwise apply the first and silently drop the other two.
     *
     * Never goes below one slot, because FX 1 is permanent — including for a preset saved before
     * slots existed, which describes none and correctly gets an empty, bypassed FX 1.
     */
    private fun syncSlots(fx: List<Preset.Fx>) {
        val slots = slots().toMutableList()
        val wanted = maxOf(fx.size, 1)

        while (slots.size > wanted) {
            val last = slots.removeAt(slots.lastIndex)
            slotButtons.remove(last)
            view.removePanel(last)
        }
        while (slots.size < wanted) {
            val slot = FxSlot()
            wireSlot(view.addPanel(slot, slots.lastOrNull()), slot)
            slots += slot
        }

        slots.forEachIndexed { index, slot ->
            val entry = fx.getOrNull(index)
            // By name, and an unknown name leaves the default — see FxSlot.selectType for why the
            // index in the values map is not trusted for this.
            slot.selectType(entry?.type ?: FxSlot.typeNames.first())
            slot.applyValues(entry?.values ?: emptyMap())
        }
        renumberSlots()
    }

    /**
     * Puts back the type each slot was on, after [Preset.applyTo] has run.
     *
     * Belt and braces, and worth the two lines. `applyTo` restores every parameter including the
     * slot's own Type, which is stored as a positional index; if the list of types is ever appended
     * to in a way that moves one, that index is stale while the name in the preset's `fx` list is
     * not. Applying the name last means the name wins.
     */
    private fun reselectSlotTypes(fx: List<Preset.Fx>) {
        slots().forEachIndexed { index, slot ->
            fx.getOrNull(index)?.let { slot.selectType(it.type) }
        }
    }

    // --------------------------------------------------------------- presets

    /**
     * Applies a sound to this board — order, switches, knobs, slots and files — with the stream
     * stopped, and returns a sentence for each file that could not be found.
     *
     * The master is not this board's to apply; the window does that against the same preset.
     */
    fun apply(sound: Preset): List<String> {
        // Before applyTo, which can match modules by name but cannot create them.
        syncSlots(sound.fx)
        // Also before applyTo, and for a subtler reason: a typed pedal's parameter list is the
        // selected type's, and applyTo reads that list once. A preset from before the dropdown
        // existed names no type and carries flat `Overdrive.Drive` keys that belong to the default
        // type, so the pedal is put on that type first and the keys then land where they should.
        for (module in typedPedals()) module.selectType(sound.modules[module.name]?.type ?: module.typeNames.first())

        val order = sound.applyTo(allEffects())
        val pedalOrder = order.filter { it !== level }
        view.setOrder(pedalOrder)
        reselectSlotTypes(sound.fx)
        // After applyTo: every type's knobs, and the name wins over the positional index — the same
        // belt and braces the slots get in reselectSlotTypes. A preset without the block has already
        // had its flat values applied to the default type, and is left exactly there.
        for (module in typedPedals()) {
            sound.modules[module.name]?.let { saved ->
                module.applyValues(saved.values)
                module.selectType(saved.type)
            }
        }
        renumberSlots()
        view.syncFromEffects()
        // replaceAll rather than reorder: the board's membership may have just changed, and reorder
        // is deliberately strict about being handed a permutation.
        chain.replaceAll(pedalOrder + level)

        // While the stream is down, so a capture is never loaded into a running callback — and so
        // its warm-up costs nothing that anyone hears. Both are attempted whatever the other does:
        // a missing cabinet must not cost you the amp as well.
        return listOfNotNull(restoreNam(sound), restoreIr(sound))
    }

    /** The fixed pedals with a type dropdown — the Overdrive — as distinct from the numbered slots. */
    private fun typedPedals(): List<TypedModule> =
        view.order.filterIsInstance<TypedModule>().filter { it !is FxSlot }

    /** This board as the second half of a split preset: no name, no master. */
    fun captureSecond(): Preset = Preset.capture("", view.order, allEffects(), loadedFileRefs())

    /** What each module currently has loaded, in the shape a preset stores. */
    fun loadedFileRefs(): Map<String, Preset.FileRef> {
        val files = LinkedHashMap<String, Preset.FileRef>()
        nam.modelName?.let { name ->
            loadedFiles[nam.name]?.let { files[nam.name] = Preset.FileRef(name, it) }
        }
        ir.impulseName?.let { name ->
            loadedFiles[ir.name]?.let { files[ir.name] = Preset.FileRef(name, it) }
        }
        return files
    }

    // ------------------------------------------------------------------- files

    /**
     * Picks an impulse response and hands it to the IR module.
     *
     * Decoding happens here, on the event thread, not in the module: it is file I/O and Java Sound,
     * neither of which may go anywhere near the audio thread. `Ir.loadImpulse` publishes the result
     * atomically, so this is safe while the stream is running.
     */
    private fun chooseImpulse() {
        val chooser = FileDialog(owner, "Load impulse response", FileDialog.LOAD).apply {
            file = "*.wav"
            openWhereFileWas(ir.name)
        }
        chooser.isVisible = true
        val directory = chooser.directory ?: return
        val name = chooser.file ?: return

        try {
            val chosen = java.io.File(directory, name)
            val impulse = ImpulseLoader.load(chosen)
            ir.loadImpulse(impulse.samples, impulse.sampleRate, impulse.name)
            loadedFiles[ir.name] = chosen.absolutePath
            missingFiles.remove(ir.name)
            irButton?.label = impulse.name.substringBeforeLast('.')
            irButton?.alert = false
            irButton?.toolTipText = chosen.absolutePath
            irClear?.isVisible = true
            host.markEdited()
            val milliseconds = impulse.samples.size * 1000f / impulse.sampleRate
            host.status(
                String.format(
                    "Loaded cabinet \"%s\" — %.0f ms at %.0f kHz, using the first %.0f ms.",
                    impulse.name,
                    milliseconds,
                    impulse.sampleRate / 1000f,
                    minOf(milliseconds, Ir.MAX_TAPS * 1000f / impulse.sampleRate),
                ),
                Theme.GOOD,
            )
        } catch (e: Exception) {
            Log.error("ir", "Could not load the impulse response \"$name\".", e)
            host.status("\"$name\" ${e.message ?: e.javaClass.simpleName}", Theme.BAD)
        }
    }

    /**
     * Picks a Neural Amp Modeler capture and hands it to the NAM module.
     *
     * Parsing is deliberately done here and not lazily in the audio path: a standard capture is
     * megabytes of JSON and thousands of weights, and none of that may happen anywhere near the
     * callback. The cost of the model is reported afterwards, because whether it will run at the
     * current buffer size depends entirely on which model was chosen.
     */
    private fun chooseNamModel() {
        val chooser = FileDialog(owner, "Load NAM capture", FileDialog.LOAD).apply {
            file = "*.nam"
            openWhereFileWas(nam.name)
        }
        chooser.isVisible = true
        val directory = chooser.directory ?: return
        val name = chooser.file ?: return

        try {
            val chosen = java.io.File(directory, name)
            val text = Files.readString(chosen.toPath())
            val loaded = NamFile.parse(text, name)
            nam.load(loaded)
            loadedFiles[nam.name] = chosen.absolutePath
            missingFiles.remove(nam.name)
            namButton?.label = name.substringBeforeLast('.')
            namButton?.alert = false
            namButton?.toolTipText = chosen.absolutePath
            namClear?.isVisible = true
            host.markEdited()

            namMenu?.isEnabled = true

            val displayRate = host.displayRate
            val perSecond = loaded.network.operationsPerSample * displayRate / 1_000_000.0
            val rateWarning = when {
                !nam.rateMismatch -> ""
                nam.matchRates -> String.format(
                    "  Captured at %s and matched to it, costing %.2f ms.",
                    SampleRates.format(loaded.sampleRate.toInt()),
                    nam.rateLatencyFrames * 1000f / displayRate,
                )
                else -> "  Captured at ${SampleRates.format(loaded.sampleRate.toInt())}, running at " +
                    "${SampleRates.format(displayRate)} — about 8 % slow, so slightly darker."
            }
            // Both of these are things the player would otherwise have to discover by ear: that
            // there is a cheaper version of this amp in the same file, and that it was quiet enough
            // to need a lot of making up.
            val sizes = if (loaded.submodels.size > 1) {
                "  Carries ${loaded.submodels.size} sizes — SIZE trades accuracy for CPU."
            } else {
                ""
            }
            val normalisation = if (nam.normalization != 1f) {
                String.format(
                    "  Levelled by %+.1f dB.",
                    20.0 * kotlin.math.log10(nam.normalization.toDouble()),
                )
            } else {
                ""
            }
            host.status(
                String.format(
                    "Loaded amp \"%s\" — %,d operations per sample, about %.0f million per second.%s%s%s",
                    name,
                    loaded.network.operationsPerSample,
                    perSecond,
                    sizes,
                    normalisation,
                    rateWarning,
                ),
                // A matched rate is no longer a warning — it is the normal case, handled.
                if (nam.rateMismatch && !nam.matchRates) Theme.BAD else Theme.GOOD,
            )
            host.ampChanged()
        } catch (e: Exception) {
            Log.error("nam", "Could not load the capture \"$name\".", e)
            host.status("\"$name\" ${e.message ?: e.javaClass.simpleName}", Theme.BAD)
        }
    }

    /**
     * The NAM pedal's overflow menu, rebuilt each time it opens.
     *
     * Rebuilt rather than kept, because everything in it depends on state that changes underneath —
     * which capture is loaded, and what rate the device settled on. A menu built once would go stale
     * the first time either changed.
     */
    private fun buildNamMenu(popup: JPopupMenu) {
        val content = DarkMenu.content()
        val displayRate = host.displayRate
        if (!nam.rateMismatch) {
            content.add(
                DarkMenu.disabledRow(
                    "Match capture rate — not needed at ${SampleRates.format(displayRate)}"
                )
            )
            popup.add(content)
            return
        }

        content.add(
            // Deliberately not markEdited(): this belongs to the device pairing, not to the patch.
            // A preset cannot store it — it does not store which capture was loaded either — so
            // flagging the preset dirty would offer to save something that cannot be saved.
            DarkMenu.switchRow("Match capture rate", nam.matchRates) { on ->
                nam.setMatchRates(on)
                announceRateMatching()
            }
        )
        content.add(
            DarkMenu.note(
                "Capture is ${SampleRates.format(nam.modelRate.toInt())}, " +
                    "device is ${SampleRates.format(displayRate)}."
            )
        )
        // The cost, stated where the choice is made. It is the entire reason this is a switch and
        // not simply always on.
        val added = nam.rateLatencyFrames
        content.add(
            DarkMenu.note(
                String.format(
                    "Costs %d frames — %.2f ms. Off, it plays about 8 %% slow.",
                    added,
                    added * 1000f / displayRate,
                )
            )
        )
        popup.add(content)
    }

    private fun announceRateMatching() {
        val displayRate = host.displayRate
        val added = nam.rateLatencyFrames
        if (nam.matchRates) {
            host.status(
                String.format(
                    "Matching the capture's %s — %+.2f ms of latency.",
                    SampleRates.format(nam.modelRate.toInt()),
                    added * 1000f / displayRate,
                ),
                Theme.GOOD,
            )
        } else {
            host.status(
                "Running the capture at ${SampleRates.format(displayRate)} — about 8 % slow, " +
                    "so it will sound slightly darker.",
                Theme.TEXT_DIM,
            )
        }
    }

    /**
     * Puts back the file a module had loaded, and reports what happened.
     *
     * Returns null on success or when the preset named no file, or a message explaining why the
     * file is not there.
     *
     * **A missing file costs the file and nothing else.** Every knob the preset restored stays
     * exactly as it was — level, mix, and the rest are all still right — so pointing at the moved
     * file is the whole of what is left to do. What the module *had* loaded is dropped rather than
     * left in place: a preset that names "GOAT", shows GOAT's settings and plays something else is
     * worse than one that plainly has no amp.
     *
     * Shared by NAM and IR so the two cannot drift apart; only the loader, the labels and the
     * wording differ.
     */
    private fun restoreFile(
        preset: Preset,
        effect: String,
        button: EffectPanel.HeaderButton?,
        emptyTooltip: String,
        clear: () -> Unit,
        load: (java.io.File) -> Unit,
    ): String? {
        missingFiles.remove(effect)
        val wanted = preset.files[effect]

        if (wanted == null) {
            // Saved without one. Anything still loaded belongs to the sound before this one.
            clear()
            loadedFiles.remove(effect)
            button?.label = "LOAD"
            button?.alert = false
            button?.toolTipText = emptyTooltip
            return null
        }

        val file = java.io.File(wanted.path)
        val problem = try {
            load(file)
            null
        } catch (e: Exception) {
            // A capture or cabinet that has moved is the most common thing to go wrong between one
            // launch and the next, and the UI's version of it is a small alert on a button.
            Log.warn("preset", "\"${wanted.path}\" for $effect could not be reloaded: ${e.message}")
            e.message ?: e.javaClass.simpleName
        }

        if (problem == null) {
            loadedFiles[effect] = wanted.path
            button?.label = file.name.substringBeforeLast('.')
            button?.alert = false
            button?.toolTipText = wanted.path
            return null
        }

        clear()
        loadedFiles.remove(effect)
        missingFiles[effect] = wanted.path
        button?.label = wanted.name.substringBeforeLast('.')
        button?.alert = true
        button?.toolTipText = "${wanted.name} is not at ${wanted.path} — click to find it"
        // A restored session has no preset name to blame — the sound came from nowhere in
        // particular — so the sentence names the file instead of an empty pair of quotes.
        val whose = if (preset.name.isBlank()) "" else "Preset \"${preset.name}\" wants "
        val what = if (whose.isEmpty()) "\"${wanted.name}\" for $effect is" else "\"${wanted.name}\" for $effect, which is"
        return "$whose$what not at ${wanted.path} — its settings were kept, so click the red " +
            "button on $effect to point at the file again."
    }

    /**
     * Points a chooser at where this module's file was last seen.
     *
     * For a preset whose file has moved, that is usually one folder from where it went — which
     * beats opening wherever the last dialog happened to leave off.
     */
    private fun FileDialog.openWhereFileWas(effect: String) {
        val previous = loadedFiles[effect] ?: missingFiles[effect] ?: return
        java.io.File(previous).parentFile
            ?.takeIf { it.isDirectory }
            ?.let { directory = it.absolutePath }
    }

    private fun restoreNam(preset: Preset): String? {
        val problem = restoreFile(
            preset,
            nam.name,
            namButton,
            "Load a Neural Amp Modeler capture",
            clear = { nam.clear() },
            load = { file -> nam.load(NamFile.parse(Files.readString(file.toPath()), file.name)) },
        )
        namMenu?.isEnabled = nam.modelName != null
        syncClearButton(nam.name, namClear, nam.modelName != null)
        return problem
    }

    /**
     * Throws away the file a module had loaded, leaving the module itself alone.
     *
     * Not confirmed, deliberately. The undo is the LOAD button sitting immediately beside it, and a
     * dialog in front of a one-click action that is one click to reverse is friction pretending to
     * be safety. The knobs are untouched, so nothing that was dialled in is lost either.
     *
     * Shared by NAM and IR so the two cannot drift; only the module, the button and the noun differ.
     */
    private fun clearLoadedFile(
        effect: String,
        button: EffectPanel.HeaderButton?,
        clearButton: EffectPanel.ClearButton?,
        emptyTooltip: String,
        noun: String,
        clear: () -> Unit,
    ) {
        clear()
        loadedFiles.remove(effect)
        missingFiles.remove(effect)
        button?.label = "LOAD"
        button?.alert = false
        button?.toolTipText = emptyTooltip
        clearButton?.isVisible = false
        host.markEdited()
        host.status("Removed the $noun from $effect. Its settings were kept.", Theme.TEXT_DIM)
    }

    private fun clearNam() {
        clearLoadedFile(
            nam.name,
            namButton,
            namClear,
            "Load a Neural Amp Modeler capture",
            "amp",
        ) { nam.clear() }
        // Everything in it describes a capture, and there is no longer one.
        namMenu?.isEnabled = false
        host.ampChanged()
    }

    private fun clearIr() = clearLoadedFile(
        ir.name,
        irButton,
        irClear,
        "Load an impulse response",
        "cabinet",
    ) { ir.clearImpulse() }

    /**
     * Shows the ✕ exactly when there is something for it to discard.
     *
     * A *missing* file counts. The module has no amp in it, but it still holds a reference that is
     * colouring the button red and putting a sentence on the status line — and "I do not have that
     * file any more, stop asking" is a thing the player has to be able to say.
     */
    private fun syncClearButton(effect: String, button: EffectPanel.ClearButton?, loaded: Boolean) {
        button?.isVisible = loaded || effect in missingFiles
    }

    private fun restoreIr(preset: Preset): String? {
        val problem = restoreFile(
            preset,
            ir.name,
            irButton,
            "Load an impulse response",
            clear = { ir.clearImpulse() },
            load = { file ->
                val impulse = ImpulseLoader.load(file)
                ir.loadImpulse(impulse.samples, impulse.sampleRate, impulse.name)
            },
        )
        syncClearButton(ir.name, irClear, ir.impulseName != null)
        return problem
    }
}
