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
package org.example.dsp.nam

import org.example.Allocation
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The block-wise [WaveNet] against the sample-by-sample one it replaced, bit for bit, over the
 * shapes a file can have and blocks of every awkward size.
 */
class WaveNetBlockTest {

    private fun array(
        inputSize: Int, channels: Int, kernels: IntArray, dilations: IntArray, activation: String,
        gating: Int = Gating.NONE, bottleneck: Int = channels, head1x1: Int = 0, headChannels: Int = 1, headKernel: Int = 1,
    ): LayerArrayConfig {
        val layers = dilations.size
        return LayerArrayConfig(
            inputSize = inputSize, conditionSize = 1, channels = channels, bottleneck = bottleneck,
            kernelSizes = kernels, dilations = dilations,
            primary = Array(layers) { Activation.of(activation) }, gating = IntArray(layers) { gating },
            secondary = Array(layers) { null }, layer1x1 = true, head1x1 = head1x1 > 0, head1x1Channels = head1x1,
            headChannels = headChannels, headKernelSize = headKernel, headBias = true,
        )
    }

    /** The full-size layers of the captures this was measured on. */
    private val shipped = array(
        1, 8,
        intArrayOf(6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 15, 15, 6, 6, 6, 6, 6, 6, 6),
        intArrayOf(1, 3, 7, 17, 41, 101, 239, 1, 3, 7, 17, 41, 101, 239, 1, 13, 1, 3, 7, 17, 41, 101, 239),
        "LeakyReLU", headKernel = 16,
    )

    private val classic = listOf(
        array(1, 16, IntArray(10) { 3 }, IntArray(10) { 1 shl it }, "Tanh", headChannels = 8),
        array(16, 8, IntArray(10) { 3 }, IntArray(10) { 1 shl it }, "Tanh"),
    )

    private fun same(configs: List<LayerArrayConfig>, head: HeadConfig? = null) {
        val random = Random(7)
        val weights = FloatArray(WaveNet.weightCount(configs, head)) { (random.nextGaussian() * 0.15).toFloat() }
        weights[weights.size - 1] = 1f // the output scale: a small one would be silence
        val ours = WaveNet.build(configs, head, weights)
        val reference = referenceNet(configs, head, weights)
        repeat(2) {
            var loud = 0f
            for (size in intArrayOf(1, 127, 128, 129, 3, 64, 256, 441, 1024, 1, 2048, 37, 5000)) {
                val input = FloatArray(size) { (random.nextGaussian() * 0.3).toFloat() }
                val a = input.copyOf()
                val b = input.copyOf()
                ours.process(a, size)
                reference.process(b, size)
                for (i in 0 until size) {
                    // Zero may differ in sign, which no sample a listener or a file sees can.
                    if (a[i] != b[i]) assertEquals(b[i], a[i], "block of $size, frame $i")
                    loud = maxOf(loud, Math.abs(a[i]))
                }
            }
            assertTrue(loud > 1e-3f && loud.isFinite(), "the network was silent, so this compared nothing: $loud")
            ours.reset()
            reference.reset()
        }
    }

    @Test fun `the shipped captures' shape`() = same(listOf(shipped))

    @Test fun `the classic two-array tanh shape`() = same(classic)

    @Test fun `a gated layer`() = same(listOf(array(1, 6, IntArray(4) { 3 }, intArrayOf(1, 2, 4, 8), "Tanh", Gating.MULTIPLY)))

    @Test fun `a blended layer with a narrow bottleneck and a head 1x1`() =
        same(listOf(array(1, 7, IntArray(3) { 2 }, intArrayOf(1, 5, 300), "ReLU", Gating.BLEND, bottleneck = 3, head1x1 = 5)))

    @Test fun `a network head`() =
        same(listOf(array(1, 4, IntArray(3) { 3 }, intArrayOf(1, 2, 4), "Softsign", headChannels = 3)),
            HeadConfig(5, 1, intArrayOf(3, 2), Activation.of("LeakyHardtanh")))

    @Test
    fun `it allocates nothing`() {
        val random = Random(1)
        val weights = FloatArray(WaveNet.weightCount(listOf(shipped), null)) { (random.nextGaussian() * 0.15).toFloat() }
        val net = WaveNet.build(listOf(shipped), null, weights)
        val block = FloatArray(256)
        repeat(200) { net.process(block, 256) }
        val before = Allocation.currentThreadBytes()
        repeat(1000) { net.process(block, 256) }
        assertTrue(Allocation.currentThreadBytes() - before < 64 * 1024)
    }
}
