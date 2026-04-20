package com.tunisianprayertimes.wake

import java.util.Collections
import java.util.Random

internal fun wakeUpCheckChallengeFor(eventId: String, triggerAtMillis: Long): WakeUpCheckChallenge {
    val sequence = (triggerAtMillis / 60_000L).coerceAtLeast(0L)
    val seed = triggerAtMillis xor eventId.hashCode().toLong()
    return wakeUpCheckChallengeAt(sequence, seed)
}

private fun wakeUpCheckChallengeAt(sequence: Long, seed: Long): WakeUpCheckChallenge {
    require(sequence >= 0L) { "sequence must be non-negative" }

    val cycle = sequence / baseChallenges.size
    require(cycle <= Int.MAX_VALUE / CYCLE_LEFT_OPERAND_OFFSET) { "sequence is too large" }

    val shuffledChallenges = baseChallenges.toMutableList().apply {
        Collections.shuffle(this, Random(seed xor (cycle * CYCLE_SHUFFLE_MULTIPLIER)))
    }
    val baseChallenge = shuffledChallenges[(sequence % baseChallenges.size).toInt()]
    val leftOperandOffset = (cycle * CYCLE_LEFT_OPERAND_OFFSET).toInt()

    return baseChallenge.copy(
        leftOperand = baseChallenge.leftOperand + leftOperandOffset,
        answer = baseChallenge.answer + leftOperandOffset,
    )
}

private val baseChallenges: List<WakeUpCheckChallenge> = buildList {
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
                    operatorSymbol = "-",
                    answer = difference,
                ),
            )
        }
    }
}

private const val CYCLE_LEFT_OPERAND_OFFSET = 16
private const val CYCLE_SHUFFLE_MULTIPLIER = 1_103_515_245L