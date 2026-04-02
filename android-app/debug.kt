import java.util.*

fun main() {
    val now = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 5)
        set(Calendar.MINUTE, 30)
    }

    val fixedHour = 5
    val fixedMinute = 31

    val unsilenceTime = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, fixedHour)
        set(Calendar.MINUTE, fixedMinute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    println(unsilenceTime.time)
}

