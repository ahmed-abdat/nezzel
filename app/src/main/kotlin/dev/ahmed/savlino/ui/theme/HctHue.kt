/*
 * Copyright 2021 Google LLC
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * Hue-only Kotlin adaptation of Material Color Utilities Cam16/ViewingConditions/ColorUtils.
 * https://github.com/material-foundation/material-color-utilities/tree/main/java
 */
package dev.ahmed.savlino.ui.theme

import kotlin.math.*

/** HCT uses CAM16 hue. Compute it in the standard D65 viewing conditions, not HSV/HSL. */
fun hctHue(argb: Int): Double {
    fun linear(component: Int): Double {
        val v = component / 255.0
        return 100 * if (v <= 0.040449936) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }
    val r = linear(argb shr 16 and 255)
    val g = linear(argb shr 8 and 255)
    val b = linear(argb and 255)
    val x = .41233895 * r + .35762064 * g + .18051042 * b
    val y = .2126 * r + .7152 * g + .0722 * b
    val z = .01932141 * r + .11916382 * g + .95034478 * b
    val white = doubleArrayOf(95.047, 100.0, 108.883)
    val matrix = arrayOf(doubleArrayOf(.401288, .650173, -.051461),
        doubleArrayOf(-.250268, 1.204414, .045854), doubleArrayOf(-.002079, .048952, .953127))
    val adapting = 200.0 / PI * ((50.0 + 16.0) / 116.0).pow(3)
    val d = (1.0 - exp((-adapting - 42.0) / 92.0) / 3.6).coerceIn(0.0, 1.0)
    val k4 = (1 / (5 * adapting + 1)).pow(4)
    val fl = k4 * adapting + .1 * (1 - k4).pow(2) * (5 * adapting).pow(1.0 / 3)
    val response = matrix.map { row ->
        val whiteResponse = row.indices.sumOf { row[it] * white[it] }
        val discounted = (d * 100 / whiteResponse + 1 - d) * (row[0] * x + row[1] * y + row[2] * z)
        val factor = (fl * abs(discounted) / 100).pow(.42)
        sign(discounted) * 400 * factor / (factor + 27.13)
    }
    val a = (11 * response[0] - 12 * response[1] + response[2]) / 11
    val opponentB = (response[0] + response[1] - 2 * response[2]) / 9
    return (Math.toDegrees(atan2(opponentB, a)) + 360) % 360
}
