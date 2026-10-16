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
package org.example.dsp

/**
 * The effect chain, in signal order.
 *
 * The default order matches how these pedals are conventionally wired for guitar:
 * master (level/mute) -> gain (level trim) -> overdrive (distortion) -> delay -> reverb. Putting
 * time-based effects after distortion keeps the repeats and tail clean instead of feeding them back
 * into the clipper. The user can [reorder] away from that, because on a real board they could too.
 */
class EffectChain(effects: List<AudioProcessor>) {

    /**
     * Signal order, as an array rather than a `List` for two reasons.
     *
     * Iterating a `List` allocates an iterator, and this is walked every block — the same defect
     * that made `Reverb` allocate 96 000 objects a second. And the array is never mutated in place:
     * [reorder] publishes a whole new one, so the audio thread's single volatile read always yields
     * a complete, self-consistent chain even if the UI swaps the order mid-block.
     */
    @Volatile
    var effects: Array<AudioProcessor> = effects.toTypedArray()
        private set

    /**
     * Replaces the signal order. [order] must be a permutation of the current chain — this moves
     * effects around, it never adds or removes them.
     *
     * Safe to call while the stream runs (the publish is atomic), though the UI forbids it: a block
     * processed with a half-changed order would be audible even though it would not be a crash.
     */
    fun reorder(order: List<AudioProcessor>) {
        val current = effects
        require(order.size == current.size && order.toHashSet() == current.toHashSet()) {
            "reorder expects a permutation of the existing chain, got ${order.map { it.name }}"
        }
        replaceAll(order)
    }

    /**
     * Replaces the chain outright, including its membership — an [FxSlot] added or deleted.
     *
     * Separate from [reorder] rather than a relaxation of it, because the check [reorder] makes is
     * worth keeping: a drag can only ever permute, so a drag that changed the membership is a bug
     * and should say so rather than quietly drop a pedal out of the signal path.
     *
     * The publish itself is a single volatile store of a fully built array, so the audio thread
     * never walks a half-changed chain. What it does *not* do is make the new effect ready — an
     * arrival must already have been through `prepare` and the warm-up before it appears here, and
     * a departure's tail is cut off wherever it happened to be. Both are why the caller only does
     * this with the stream stopped.
     */
    fun replaceAll(order: List<AudioProcessor>) {
        effects = order.toTypedArray()
    }

    fun process(buffer: FloatArray, count: Int) {
        // One volatile read, then work from the local: re-reading the field per effect could walk
        // two different chains within a single block.
        val chain = effects
        for (effect in chain) {
            // A muting effect still runs while disabled — its process() ramps down to silence.
            // Skipping it would pass the signal through untouched, the opposite of what its
            // switch means.
            if (effect.enabled || effect.mutesWhenDisabled) effect.process(buffer, count)
        }
    }

    /** Binds every effect to the device rate and block size. Called off the audio thread. */
    fun prepare(sampleRate: Float, maxBlockFrames: Int) {
        effects.forEach { it.prepare(sampleRate, maxBlockFrames) }
    }

    fun reset() {
        effects.forEach { it.reset() }
    }
}
