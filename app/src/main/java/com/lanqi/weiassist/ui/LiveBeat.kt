package com.lanqi.weiassist.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import com.lanqi.weiassist.agent.STREAM_FRAME_MS
import com.lanqi.weiassist.agent.liveGridWait
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlin.math.PI
import kotlin.math.sin

/**
 * One clock for everything that moves while the agent works (the swimming fish, the working dots, shimmering
 * steps). Live work is shown in a few steps a second, not frame by frame: Android draws the whole window again for
 * any change, and a page of text costs this phone ~30 ms to draw, so a 60 fps animation kept it busy for the whole
 * run. One clock, so that all of them change in the same frame: separate timers would each cost frames of their own.
 * It ticks on the grid streamed text also arrives on ([liveGridWait], every [STREAM_FRAME_MS]), so while an answer
 * streams, a new pose is drawn in a frame that was drawn for the text anyway. It ticks only while something on
 * screen listens.
 */
private object LiveBeat {
    const val MS = STREAM_FRAME_MS * 2
    val ticks: StateFlow<Int> = flow {
        var i = 0
        while (true) { delay(liveGridWait(MS)); emit(++i) }
    }.stateIn(MainScope(), SharingStarted.WhileSubscribed(), 0)
}

/**
 * A value that steps from 0 to [steps] - 1 and round again, one step every [beats] ticks of the live clock
 * ([LiveBeat.MS] each). Read it where it is drawn when possible: then a step redraws without recomposing.
 */
@Composable
fun steppingPhase(steps: Int, beats: Int = 1): State<Int> {
    val ticks = LiveBeat.ticks.collectAsState()
    return remember(steps, beats) { derivedStateOf { ticks.value / beats % steps } }
}

// ---- the swimming fish ---------------------------------------------------------------------------------

/** The app icon's tuna (ic_launcher_foreground.xml), in its viewport units, cropped to the fish and its bubbles. */
private object Tuna {
    const val LEFT = 23f
    const val TOP = 25f
    const val WIDTH = 66f
    const val HEIGHT = 53f
    // The icon draws it 4 units lower (its group's translateY); these are the icon's own coordinates.
    const val SHIFT = 4f

    private fun path(d: String): Path = PathParser().parsePathString(d).toPath()

    val tail = path("M70,54L83,41Q78.5,54 83,67Z")
    val dorsal = path("M45,40Q52,28 62,41Z")
    val body = path("M27,55C27,44 39,38 51,38C62,38 68,46 73,54C68,62 62,70 51,70C39,70 27,66 27,55Z")
    val belly = path("M29,60C35,67 43,70 51,70C61,70 67,63 71,57C60,62 43,64 29,60Z")
    val fin = path("M50,55Q57,57 57,63Q51,62 50,55Z")
    val smile = path("M30,59Q32.5,61 35,59")

    val dark = Color(0xFF2F6FE0)
    val blue = Color(0xFF3D86F5)
    val pale = Color(0xFFCDE8FF)
    val ink = Color(0xFF14295C)
    val blush = Color(0xCCFF9EB5)
    /** The icon's bubbles are white on its blue background; on the page they need the fish's own blue. */
    val bubble = Color(0xFF6FA8F7)
}

/** Poses of one swim cycle: a little over a second at the live clock. */
private const val POSES = 6

/**
 * The app's tuna swimming: its tail beats, it bobs and its bubbles rise, one pose per tick of the live clock
 * (see [LiveBeat]: a few poses a second, never a frame-by-frame animation). Only the drawing reads the pose, so
 * a step costs a redraw of this small canvas and no recomposition.
 */
@Composable
fun SwimmingFish(modifier: Modifier = Modifier) {
    val pose = steppingPhase(POSES)
    Canvas(modifier.size(26.dp, 21.dp)) { drawTuna(pose.value) }
}

private fun DrawScope.drawTuna(pose: Int) {
    val t = 2 * PI * pose / POSES
    val tail = (14 * sin(t)).toFloat()           // degrees, around where the tail meets the body
    val bob = (1.6 * sin(t + PI / 2)).toFloat()  // viewport units, up and down
    val s = minOf(size.width / Tuna.WIDTH, size.height / Tuna.HEIGHT)
    scale(s, s, pivot = Offset.Zero) {
        translate(-Tuna.LEFT, -Tuna.TOP + Tuna.SHIFT) {
            // Bubbles first, rising and fading over the cycle; the small one half a cycle behind.
            bubble(30f, 38f, 2.6f, 1.6f, pose)
            bubble(35f, 31f, 1.8f, 1.4f, (pose + POSES / 2) % POSES)
            translate(0f, bob) {
                // The whole fish leans a little against its tail: a wiggle, not a stiff slide.
                rotate(-tail / 5, pivot = Offset(51f, 54f)) {
                    rotate(tail, pivot = Offset(71f, 54f)) {
                        drawPath(Tuna.tail, Tuna.dark, style = Fill)
                        drawPath(Tuna.tail, Tuna.dark, style = Stroke(4f, join = StrokeJoin.Round))
                    }
                    drawPath(Tuna.dorsal, Tuna.dark, style = Fill)
                    drawPath(Tuna.dorsal, Tuna.dark, style = Stroke(3f, join = StrokeJoin.Round))
                    drawPath(Tuna.body, Tuna.blue)
                    drawPath(Tuna.belly, Tuna.pale)
                    drawPath(Tuna.fin, Tuna.dark)
                    drawCircle(Tuna.ink, 4f, Offset(38f, 51f))
                    drawCircle(Color.White, 1.4f, Offset(39.3f, 49.6f))
                    drawPath(Tuna.smile, Tuna.ink, style = Stroke(1.6f, cap = StrokeCap.Round))
                    drawCircle(Tuna.blush, 2.6f, Offset(43f, 58f))
                }
            }
        }
    }
}

/** A bubble at ([x], [y]) that rises 2 units a pose and fades as it goes. */
private fun DrawScope.bubble(x: Float, y: Float, r: Float, width: Float, pose: Int) {
    val alpha = 1f - pose / POSES.toFloat()
    drawCircle(Tuna.bubble.copy(alpha = alpha), r, Offset(x, y - 2f * pose), style = Stroke(width))
}
