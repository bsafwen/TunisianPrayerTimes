package com.tunisianprayertimes.wake

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tunisianprayertimes.MathDifficulty
import com.tunisianprayertimes.R
import kotlin.math.sqrt
import kotlin.random.Random

// ─────────────────────────────────────────────────────────────────────────────
// Game parameters per difficulty
// ─────────────────────────────────────────────────────────────────────────────

private data class MazeSpec(
    /** Hold duration required to win (seconds). */
    val holdSeconds: Int,
    /** Target circle radius as a fraction of min(canvas width, canvas height). */
    val targetRadiusFraction: Float,
    /** Ball radius as a fraction of min(canvas width, canvas height). */
    val ballRadiusFraction: Float,
    /** Responsiveness: canvas-units/s² when phone is tilted 1 g. */
    val accelScale: Float,
    /** Linear-acceleration magnitude above which a shake resets the hold timer (m/s²). */
    val shakeThreshold: Float,
    /** Number of holes the ball can fall into. 0 on Easy. */
    val holeCount: Int,
    /** Whether to generate a proper corridor maze. */
    val useMaze: Boolean,
    /** Maze grid columns (only when useMaze = true). */
    val mazeCols: Int,
    /** Maze grid rows (only when useMaze = true). */
    val mazeRows: Int,
)

private fun specForDifficulty(difficulty: MathDifficulty): MazeSpec = when (difficulty) {
    MathDifficulty.EASY -> MazeSpec(
        holdSeconds = 5,
        targetRadiusFraction = 0.065f,
        ballRadiusFraction = 0.038f,
        accelScale = 1.8f,
        shakeThreshold = 20f,
        holeCount = 0,
        useMaze = true,
        mazeCols = 4,
        mazeRows = 5,
    )
    MathDifficulty.INTERMEDIATE -> MazeSpec(
        holdSeconds = 5,
        targetRadiusFraction = 0.050f,
        ballRadiusFraction = 0.032f,
        accelScale = 1.5f,
        shakeThreshold = 15f,
        holeCount = 2,
        useMaze = true,
        mazeCols = 5,
        mazeRows = 7,
    )
    MathDifficulty.HARD -> MazeSpec(
        holdSeconds = 5,
        targetRadiusFraction = 0.035f,
        ballRadiusFraction = 0.022f,
        accelScale = 1.2f,
        shakeThreshold = 12f,
        holeCount = 6,
        useMaze = true,
        mazeCols = 8,
        mazeRows = 10,
    )
}

// (Ball start and target positions are now determined per-level by generateLevel())

// ─────────────────────────────────────────────────────────────────────────────
// Geometry generation (all coordinates normalized 0..1)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Result of generating the full level geometry.
 * Walls and holes are in normalized 0..1 coordinates.
 */
private data class LevelGeometry(
    val walls: List<Rect>,
    val holes: List<Offset>,
    val ballStart: Offset,
    val targetPos: Offset,
)

/**
 * Generate the full level geometry.
 *
 * - Easy / Intermediate: open arena (no walls), holes placed on a grid.
 * - Hard: a proper corridor maze generated via recursive-backtracking DFS, with holes placed
 *   inside corridors away from start/target.
 */
private fun generateLevel(seed: Long, spec: MazeSpec): LevelGeometry {
    if (!spec.useMaze) {
        // Open arena for Easy / Intermediate
        val start = Offset(0.25f, 0.20f)
        val target = Offset(0.72f, 0.73f)
        val holes = generateOpenArenaHoles(seed, spec.holeCount, start, target)
        return LevelGeometry(walls = emptyList(), holes = holes, ballStart = start, targetPos = target)
    }

    // ── Hard: real maze ──────────────────────────────────────────────────────
    val cols = spec.mazeCols
    val rows = spec.mazeRows
    val rng = Random(seed)

    // Margin around the maze inside the 0..1 canvas
    val margin = 0.04f
    val wallThick = 0.022f
    val cellW = (1f - 2 * margin) / cols
    val cellH = (1f - 2 * margin) / rows

    // Ball starts in the top-left cell center, target in the bottom-right cell center.
    val startCell = 0 to 0
    val endCell = (cols - 1) to (rows - 1)
    val ballStart = Offset(
        x = margin + (startCell.first + 0.5f) * cellW,
        y = margin + (startCell.second + 0.5f) * cellH,
    )
    val targetPos = Offset(
        x = margin + (endCell.first + 0.5f) * cellW,
        y = margin + (endCell.second + 0.5f) * cellH,
    )

    // ── Recursive-backtracking DFS to carve passages ─────────────────────────
    // Each cell stores which walls have been removed (passage opened).
    // Bit 0 = top, 1 = right, 2 = bottom, 3 = left
    val TOP = 0; val RIGHT = 1; val BOTTOM = 2; val LEFT = 3
    val passages = Array(cols * rows) { BooleanArray(4) }
    val visited = BooleanArray(cols * rows)
    val stack = ArrayDeque<Int>()

    fun idx(c: Int, r: Int) = r * cols + c
    fun neighbors(c: Int, r: Int): List<Triple<Int, Int, Int>> = buildList {
        if (r > 0)        add(Triple(c, r - 1, TOP))
        if (c < cols - 1) add(Triple(c + 1, r, RIGHT))
        if (r < rows - 1) add(Triple(c, r + 1, BOTTOM))
        if (c > 0)        add(Triple(c - 1, r, LEFT))
    }
    fun opposite(dir: Int) = when (dir) {
        TOP -> BOTTOM; BOTTOM -> TOP; LEFT -> RIGHT; else -> LEFT
    }

    var cur = idx(startCell.first, startCell.second)
    visited[cur] = true
    stack.addLast(cur)
    while (stack.isNotEmpty()) {
        val cc = cur % cols
        val cr = cur / cols
        val unvisited = neighbors(cc, cr).filter { (nc, nr, _) -> !visited[idx(nc, nr)] }
        if (unvisited.isNotEmpty()) {
            val (nc, nr, dir) = unvisited[rng.nextInt(unvisited.size)]
            passages[cur][dir] = true
            val next = idx(nc, nr)
            passages[next][opposite(dir)] = true
            visited[next] = true
            stack.addLast(cur)
            cur = next
        } else {
            cur = stack.removeLast()
        }
    }

    // ── Convert cell walls to Rect segments ──────────────────────────────────
    val walls = buildList<Rect> {
        // Outer border (4 rectangles)
        add(Rect(margin, margin, 1f - margin, margin + wallThick))                       // top
        add(Rect(margin, 1f - margin - wallThick, 1f - margin, 1f - margin))             // bottom
        add(Rect(margin, margin, margin + wallThick, 1f - margin))                       // left
        add(Rect(1f - margin - wallThick, margin, 1f - margin, 1f - margin))             // right

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val i = idx(c, r)
                val x0 = margin + c * cellW
                val y0 = margin + r * cellH
                // Right wall of cell (unless passage or last column — outer border already covers it)
                if (c < cols - 1 && !passages[i][RIGHT]) {
                    val wx = x0 + cellW - wallThick / 2
                    add(Rect(wx, y0, wx + wallThick, y0 + cellH))
                }
                // Bottom wall of cell (unless passage or last row)
                if (r < rows - 1 && !passages[i][BOTTOM]) {
                    val wy = y0 + cellH - wallThick / 2
                    add(Rect(x0, wy, x0 + cellW, wy + wallThick))
                }
            }
        }
    }

    // ── BFS to find the solution path (start → end) ───────────────────────────
    // In a perfect maze every cell is reachable and there is exactly one path.
    // Holes must NOT be placed on this path, otherwise the game is unsolvable.
    val solutionCells: Set<Int> = run {
        val prev = IntArray(cols * rows) { -1 }
        val bfsVisited = BooleanArray(cols * rows)
        val queue = ArrayDeque<Int>()
        val startIdx = idx(startCell.first, startCell.second)
        val endIdx = idx(endCell.first, endCell.second)
        queue.addLast(startIdx)
        bfsVisited[startIdx] = true
        while (queue.isNotEmpty()) {
            val ci = queue.removeFirst()
            if (ci == endIdx) break
            val cc = ci % cols
            val cr = ci / cols
            val dirs = listOf(
                Triple(0, -1, TOP), Triple(1, 0, RIGHT),
                Triple(0, 1, BOTTOM), Triple(-1, 0, LEFT),
            )
            for ((dc, dr, dir) in dirs) {
                if (!passages[ci][dir]) continue
                val nc = cc + dc
                val nr = cr + dr
                if (nc !in 0 until cols || nr !in 0 until rows) continue
                val ni = idx(nc, nr)
                if (bfsVisited[ni]) continue
                bfsVisited[ni] = true
                prev[ni] = ci
                queue.addLast(ni)
            }
        }
        // Trace back from end to start
        val path = mutableSetOf<Int>()
        var node = endIdx
        while (node != -1) {
            path.add(node)
            node = prev[node]
        }
        path
    }

    // ── Place holes in corridor cells that are NOT on the solution path ──────
    val holeCandidates = buildList {
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val i = idx(c, r)
                // Never on the solution path
                if (i in solutionCells) continue
                // Also skip cells adjacent to start/end for fairness
                val distToStart = kotlin.math.abs(c - startCell.first) + kotlin.math.abs(r - startCell.second)
                val distToEnd = kotlin.math.abs(c - endCell.first) + kotlin.math.abs(r - endCell.second)
                if (distToStart <= 1 || distToEnd <= 1) continue
                add(Offset(margin + (c + 0.5f) * cellW, margin + (r + 0.5f) * cellH))
            }
        }
    }.shuffled(rng)
    val holes = holeCandidates.take(spec.holeCount)

    return LevelGeometry(walls = walls, holes = holes, ballStart = ballStart, targetPos = targetPos)
}

/**
 * For Easy / Intermediate: place holes on a shuffled grid in the open arena.
 */
private fun generateOpenArenaHoles(seed: Long, count: Int, start: Offset, target: Offset): List<Offset> {
    if (count == 0) return emptyList()
    val rng = Random(seed)
    val cols = 4
    val rows = 4
    val cellW = 0.84f / cols
    val cellH = 0.80f / rows
    val candidates = buildList {
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val cx = 0.08f + (c + 0.5f) * cellW
                val cy = 0.10f + (r + 0.5f) * cellH
                val pos = Offset(cx, cy)
                if (pos.distanceTo(start) < 0.18f) continue
                if (pos.distanceTo(target) < 0.18f) continue
                add(pos)
            }
        }
    }.shuffled(rng)
    return candidates.take(count).map { pos ->
        Offset(
            x = (pos.x + rng.nextFloat() * 0.06f - 0.03f).coerceIn(0.06f, 0.94f),
            y = (pos.y + rng.nextFloat() * 0.06f - 0.03f).coerceIn(0.08f, 0.92f),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Composable
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun GyroscopeMazeGame(
    difficulty: MathDifficulty,
    onCompleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val spec = remember(difficulty) { specForDifficulty(difficulty) }

    // Stable seed for the session (survives recomposition, resets on new alarm event)
    val seed = rememberSaveable { Random.nextLong() }

    val level = remember(seed, spec) { generateLevel(seed, spec) }
    val walls = level.walls
    val holes = level.holes
    val ballStartPos = level.ballStart
    val targetPos = level.targetPos

    // ── Sensor state (updated from listener, read in physics loop) ──────────
    // tiltX > 0 → device tilts right  → ball should accelerate rightward (+x on screen)
    // tiltY > 0 → device tilts forward (top away) → ball should accelerate downward (+y on screen)
    // Convention from TYPE_GRAVITY:
    //   values[0]: +x when right side is lower → tiltX = +values[0]
    //   values[1]: +y when top side is lower  → tiltY = -values[1]  (Y-axis invert for screen coords)
    var tiltX by remember { mutableFloatStateOf(0f) }
    var tiltY by remember { mutableFloatStateOf(0f) }
    var shakeDetected by remember { mutableStateOf(false) }

    // ── Physics state (normalized canvas coordinates 0..1) ──────────────────
    var ballX by remember { mutableFloatStateOf(ballStartPos.x) }
    var ballY by remember { mutableFloatStateOf(ballStartPos.y) }
    var velX by remember { mutableFloatStateOf(0f) }
    var velY by remember { mutableFloatStateOf(0f) }

    // ── Game state ───────────────────────────────────────────────────────────
    var holdMs by rememberSaveable { mutableLongStateOf(0L) }
    var completed by rememberSaveable { mutableStateOf(false) }
    // "shake" | "hole" | null
    var resetReason by remember { mutableStateOf<String?>(null) }

    // ── Sensor registration ──────────────────────────────────────────────────
    DisposableEffect(context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // Prefer GRAVITY (smooth, already low-pass filtered) for tilt.
        // Fall back to raw ACCELEROMETER which contains gravity + noise.
        val tiltSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // TYPE_LINEAR_ACCELERATION excludes gravity — ideal for shake detection.
        val shakeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_GRAVITY,
                    Sensor.TYPE_ACCELEROMETER -> {
                        // values[0]: positive when right side is lower (tilt left)
                        //   → ball should roll left (negative screen X) → negate
                        // values[1]: positive when top side is lower (tilt forward)
                        //   → ball should roll toward top (negative screen Y) → keep sign
                        tiltX = -event.values[0] / SensorManager.GRAVITY_EARTH
                        tiltY = event.values[1] / SensorManager.GRAVITY_EARTH
                    }
                    Sensor.TYPE_LINEAR_ACCELERATION -> {
                        val mag = sqrt(
                            event.values[0] * event.values[0] +
                                event.values[1] * event.values[1] +
                                event.values[2] * event.values[2],
                        )
                        if (mag > spec.shakeThreshold) shakeDetected = true
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }

        if (tiltSensor != null) {
            sensorManager.registerListener(listener, tiltSensor, SensorManager.SENSOR_DELAY_GAME)
        }
        shakeSensor?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
        }

        onDispose { sensorManager.unregisterListener(listener) }
    }

    // ── Physics + hold-timer loop ────────────────────────────────────────────
    LaunchedEffect(spec) {
        if (completed) return@LaunchedEffect

        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val hasTiltSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY) != null ||
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null

        if (!hasTiltSensor) {
            // Safety net: no sensor available — unlock immediately rather than trap the user.
            completed = true
            onCompleted()
            return@LaunchedEffect
        }

        val br = spec.ballRadiusFraction
        val holeRadius = br * 1.0f   // hole "catch" radius ≈ ball radius

        var lastNanos = 0L

        while (!completed) {
            withFrameNanos { frameNanos ->
                if (lastNanos == 0L) {
                    lastNanos = frameNanos
                    return@withFrameNanos
                }
                val dtSec = ((frameNanos - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
                lastNanos = frameNanos

                // ── Shake reset ──
                if (shakeDetected) {
                    shakeDetected = false
                    velX = 0f
                    velY = 0f
                    holdMs = 0L
                    resetReason = "shake"
                    return@withFrameNanos
                }

                // ── Apply tilt acceleration with per-frame damping ──
                val ax = tiltX * spec.accelScale
                val ay = tiltY * spec.accelScale
                velX = velX * 0.92f + ax * dtSec
                velY = velY * 0.92f + ay * dtSec

                var nx = ballX + velX * dtSec
                var ny = ballY + velY * dtSec

                // ── Canvas boundary ──
                if (nx < br) { nx = br; velX = -velX * 0.4f }
                if (nx > 1f - br) { nx = 1f - br; velX = -velX * 0.4f }
                if (ny < br) { ny = br; velY = -velY * 0.4f }
                if (ny > 1f - br) { ny = 1f - br; velY = -velY * 0.4f }

                // ── Maze wall collisions (circle vs. AABB) ──
                for (wall in walls) {
                    val cx = nx.coerceIn(wall.left, wall.right)
                    val cy = ny.coerceIn(wall.top, wall.bottom)
                    val dx = nx - cx
                    val dy = ny - cy
                    val dist = sqrt(dx * dx + dy * dy)
                    if (dist < br && dist > 0f) {
                        val pen = br - dist
                        val invDist = 1f / dist
                        nx += dx * invDist * pen
                        ny += dy * invDist * pen
                        val dot = velX * dx * invDist + velY * dy * invDist
                        velX -= 2f * dot * dx * invDist * 0.5f
                        velY -= 2f * dot * dy * invDist * 0.5f
                    }
                }

                // ── Hole check ──
                var fellInHole = false
                for (hole in holes) {
                    val dx = nx - hole.x
                    val dy = ny - hole.y
                    if (dx * dx + dy * dy < (holeRadius + br) * (holeRadius + br)) {
                        fellInHole = true
                        break
                    }
                }

                if (fellInHole) {
                    nx = ballStartPos.x
                    ny = ballStartPos.y
                    velX = 0f
                    velY = 0f
                    holdMs = 0L
                    resetReason = "hole"
                    ballX = nx
                    ballY = ny
                    return@withFrameNanos
                }

                ballX = nx
                ballY = ny

                // ── Hold timer ──
                val dx = nx - targetPos.x
                val dy = ny - targetPos.y
                val inTarget = dx * dx + dy * dy < spec.targetRadiusFraction * spec.targetRadiusFraction
                if (inTarget) {
                    holdMs += (dtSec * 1000f).toLong()
                    resetReason = null
                    if (holdMs >= spec.holdSeconds * 1000L) {
                        completed = true
                        onCompleted()
                    }
                } else {
                    if (holdMs > 0L) holdMs = 0L
                }
            }
        }
    }

    // ── Strings (resolved outside the Canvas draw call) ─────────────────────
    val promptText = stringResource(R.string.wake_alarm_gyroscope_maze_prompt)
    val holdProgressText = if (!completed && holdMs > 0L) {
        stringResource(
            R.string.wake_alarm_gyroscope_maze_hold_progress,
            (holdMs / 1000L).toInt(),
            spec.holdSeconds,
        )
    } else ""
    val completeText = stringResource(R.string.wake_alarm_gyroscope_maze_complete)
    val shakeResetText = stringResource(R.string.wake_alarm_gyroscope_maze_reset_shake)
    val holeResetText = stringResource(R.string.wake_alarm_gyroscope_maze_reset_hole)

    // ── UI ───────────────────────────────────────────────────────────────────
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = promptText,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onPrimary,
        )

        if (holdMs > 0L && !completed) {
            Text(
                text = holdProgressText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }

        resetReason?.let { reason ->
            Text(
                text = if (reason == "shake") shakeResetText else holeResetText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .padding(2.dp),
        ) {
            val w = size.width
            val h = size.height
            val minDim = minOf(w, h)

            // ── Arena background ──
            drawRect(color = if (spec.useMaze) Color(0xFF263238) else Color(0xFF1A237E).copy(alpha = 0.80f))

            // ── Maze walls ──
            val wallColor = if (spec.useMaze) Color(0xFF8D6E63) else Color(0xFF546E7A)
            for (wall in walls) {
                drawRect(
                    color = wallColor,
                    topLeft = Offset(wall.left * w, wall.top * h),
                    size = Size((wall.right - wall.left) * w, (wall.bottom - wall.top) * h),
                )
            }

            // ── Holes ──
            val holeDrawRadius = spec.ballRadiusFraction * minDim
            for (hole in holes) {
                // Outer dark circle
                drawCircle(
                    color = Color(0xFF000000),
                    radius = holeDrawRadius,
                    center = Offset(hole.x * w, hole.y * h),
                )
                // Inner highlight
                drawCircle(
                    color = Color(0xFF1C1C1C),
                    radius = holeDrawRadius * 0.6f,
                    center = Offset(hole.x * w, hole.y * h),
                )
            }

            // ── Target zone ──
            val targetRadius = spec.targetRadiusFraction * minDim
            val holdFraction = (holdMs / (spec.holdSeconds * 1000f)).coerceIn(0f, 1f)
            drawCircle(
                color = Color(0xFF4CAF50).copy(alpha = 0.25f + 0.45f * holdFraction),
                radius = targetRadius,
                center = Offset(targetPos.x * w, targetPos.y * h),
            )
            drawCircle(
                color = Color(0xFF4CAF50),
                radius = targetRadius,
                center = Offset(targetPos.x * w, targetPos.y * h),
                style = Stroke(width = 3.dp.toPx()),
            )

            // ── Ball (shadow + body + specular highlight) ──
            val ballRadius = spec.ballRadiusFraction * minDim
            val bx = ballX * w
            val by = ballY * h
            drawCircle(
                color = Color(0x44000000),
                radius = ballRadius + 3.dp.toPx(),
                center = Offset(bx + 2.dp.toPx(), by + 2.dp.toPx()),
            )
            drawCircle(
                color = Color(0xFFF57F17),
                radius = ballRadius,
                center = Offset(bx, by),
            )
            drawCircle(
                color = Color(0xCCFFFFFF),
                radius = ballRadius * 0.35f,
                center = Offset(bx - ballRadius * 0.25f, by - ballRadius * 0.25f),
            )
        }

        if (completed) {
            Text(
                text = completeText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun Offset.distanceTo(other: Offset): Float {
    val dx = x - other.x
    val dy = y - other.y
    return sqrt(dx * dx + dy * dy)
}
