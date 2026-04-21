package com.tunisianprayertimes.wake

import com.tunisianprayertimes.MathDifficulty
import java.util.Collections
import java.util.Random

internal fun wakeUpCheckChallengeFor(
    eventId: String,
    triggerAtMillis: Long,
    difficulty: MathDifficulty = MathDifficulty.EASY,
): WakeUpCheckChallenge {
    val sequence = (triggerAtMillis / 60_000L).coerceAtLeast(0L)
    val seed = triggerAtMillis xor eventId.hashCode().toLong()
    return wakeUpCheckChallengeAt(sequence, seed, difficulty)
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
    for (leftOperand in 11..23 step 2) {
        for (rightOperand in 4..11) {
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

    for (rightOperand in 3..9) {
        for (difference in 8..17) {
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

private val hardChallenges: List<WakeUpCheckChallenge> = buildList {
    for (leftOperand in 12..25) {
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

    for (leftOperand in 30..99 step 3) {
        for (rightOperand in 15..49) {
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
}