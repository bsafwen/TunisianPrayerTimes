package com.tunisianprayertimes.wake

import com.tunisianprayertimes.MathDifficulty
import java.util.Collections
import java.util.Random

private const val STEP_SEED_MULTIPLIER = 7_919L

internal fun wakeUpCheckChallengeFor(
    eventId: String,
    triggerAtMillis: Long,
    difficulty: MathDifficulty = MathDifficulty.EASY,
): WakeUpCheckChallenge {
    return wakeUpCheckChallengeForStep(eventId, triggerAtMillis, 0, difficulty)
}

internal fun wakeUpCheckChallengeForStep(
    eventId: String,
    triggerAtMillis: Long?,
    stepIndex: Int,
    difficulty: MathDifficulty,
): WakeUpCheckChallenge {
    if (triggerAtMillis == null) {
        return wakeUpCheckChallengeForStep(eventId, stepIndex, difficulty)
    }

    val sequence = (triggerAtMillis / 60_000L).coerceAtLeast(0L) + stepIndex.toLong()
    val seed = triggerAtMillis xor eventId.hashCode().toLong() xor (stepIndex.toLong() * STEP_SEED_MULTIPLIER)
    return wakeUpCheckChallengeAt(sequence, seed, difficulty)
}

internal fun wakeUpCheckChallengeForStep(
    eventId: String,
    stepIndex: Int,
    difficulty: MathDifficulty,
): WakeUpCheckChallenge {
    val seed = eventId.hashCode().toLong() xor (stepIndex.toLong() * STEP_SEED_MULTIPLIER)
    return wakeUpCheckChallengeAt(stepIndex.toLong(), seed, difficulty)
}

private fun wakeUpCheckChallengeAt(
    sequence: Long,
    seed: Long,
    difficulty: MathDifficulty,
): WakeUpCheckChallenge {
    require(sequence >= 0L) { "sequence must be non-negative" }

    val challenges = challengesForDifficulty(difficulty)
    val shuffledChallenges = challenges.toMutableList().apply {
        Collections.shuffle(this, Random(seed))
    }
    return shuffledChallenges[(sequence % challenges.size).toInt()]
}

private fun challengesForDifficulty(difficulty: MathDifficulty): List<WakeUpCheckChallenge> =
    when (difficulty) {
        MathDifficulty.EASY -> easyChallenges
        MathDifficulty.INTERMEDIATE -> intermediateChallenges
        MathDifficulty.HARD -> hardChallenges
    }

private val easyChallenges: List<WakeUpCheckChallenge> = buildList {
    for (leftOperand in 2..9) {
        for (rightOperand in 1..9) {
            add(
                WakeUpCheckChallenge(
                    leftOperand = leftOperand,
                    rightOperand = rightOperand,
                    operatorSymbol = "+",
                    answer = leftOperand + rightOperand,
                ),
            )
        }
    }

    for (leftOperand in 10..18) {
        for (rightOperand in 1..9) {
            if (leftOperand > rightOperand) {
                add(
                    WakeUpCheckChallenge(
                        leftOperand = leftOperand,
                        rightOperand = rightOperand,
                        operatorSymbol = "−",
                        answer = leftOperand - rightOperand,
                    ),
                )
            }
        }
    }
}

private val intermediateChallenges: List<WakeUpCheckChallenge> = buildList {
    for (leftOperand in 15..35 step 2) {
        for (rightOperand in 7..18) {
            add(
                WakeUpCheckChallenge(
                    leftOperand = leftOperand,
                    rightOperand = rightOperand,
                    operatorSymbol = "+",
                    answer = leftOperand + rightOperand,
                ),
            )
        }
    }

    for (rightOperand in 5..14) {
        for (difference in 12..25) {
            val leftOperand = rightOperand + difference
            add(
                WakeUpCheckChallenge(
                    leftOperand = leftOperand,
                    rightOperand = rightOperand,
                    operatorSymbol = "−",
                    answer = difference,
                ),
            )
        }
    }

    for (leftOperand in 3..9) {
        for (rightOperand in 3..9) {
            add(
                WakeUpCheckChallenge(
                    leftOperand = leftOperand,
                    rightOperand = rightOperand,
                    operatorSymbol = "×",
                    answer = leftOperand * rightOperand,
                ),
            )
        }
    }
}

private val hardChallenges: List<WakeUpCheckChallenge> = buildList {
    for (leftOperand in 15..35) {
        for (rightOperand in 6..15) {
            add(
                WakeUpCheckChallenge(
                    leftOperand = leftOperand,
                    rightOperand = rightOperand,
                    operatorSymbol = "×",
                    answer = leftOperand * rightOperand,
                ),
            )
        }
    }

    for (leftOperand in 50..150 step 3) {
        for (rightOperand in 25..75) {
            if (leftOperand > rightOperand) {
                add(
                    WakeUpCheckChallenge(
                        leftOperand = leftOperand,
                        rightOperand = rightOperand,
                        operatorSymbol = "+",
                        answer = leftOperand + rightOperand,
                    ),
                )
            }
        }
    }

    for (rightOperand in 15..45) {
        for (difference in 20..55) {
            val leftOperand = rightOperand + difference
            add(
                WakeUpCheckChallenge(
                    leftOperand = leftOperand,
                    rightOperand = rightOperand,
                    operatorSymbol = "−",
                    answer = difference,
                ),
            )
        }
    }
}