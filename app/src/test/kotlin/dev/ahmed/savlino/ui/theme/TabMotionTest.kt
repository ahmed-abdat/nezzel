package dev.ahmed.savlino.ui.theme

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import org.junit.Assert.*
import org.junit.Test

class TabMotionTest {
    @Test fun fadeInWaitsSixtyMillisecondsThenFollowsTheSpring() {
        val delayed = Motion.TabFadeIn.vectorize(Float.VectorConverter)
        val spring = spring<Float>(dampingRatio = 1f, stiffness = 1600f).vectorize(Float.VectorConverter)
        val start = AnimationVector1D(0f)
        val end = AnimationVector1D(1f)
        val velocity = AnimationVector1D(0f)
        assertEquals(0f, delayed.getValueFromNanos(59_000_000, start, end, velocity).value, 0f)
        assertEquals(spring.getValueFromNanos(40_000_000, start, end, velocity).value,
            delayed.getValueFromNanos(100_000_000, start, end, velocity).value, .0001f)
        assertEquals(60_000_000 + spring.getDurationNanos(start, end, velocity), delayed.getDurationNanos(start, end, velocity))
    }
}
