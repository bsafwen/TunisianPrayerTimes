package com.tunisianprayertimes

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Magnetic field at a point, as predicted by the World Magnetic Model. */
data class GeomagneticFieldValues(
    /** Angle of magnetic north east of true north. */
    val declinationDegrees: Double,
    /** Dip of the field below the horizontal; negative in the southern magnetic hemisphere. */
    val inclinationDegrees: Double,
    val horizontalIntensityNanoTesla: Double,
    val totalIntensityNanoTesla: Double,
)

/** How far a phone compass can be trusted, given the horizontal field it has to read. */
enum class MagneticFieldZone {
    Normal,

    /** WMM "caution zone": small disturbances swing the heading by many degrees. */
    Caution,

    /** WMM "blackout zone": a compass cannot be relied on at all. */
    Blackout,
}

internal const val MAGNETIC_CAUTION_NANOTESLA = 6_000.0
internal const val MAGNETIC_BLACKOUT_NANOTESLA = 2_000.0

fun magneticFieldZone(horizontalIntensityNanoTesla: Double): MagneticFieldZone {
    return when {
        horizontalIntensityNanoTesla < MAGNETIC_BLACKOUT_NANOTESLA -> MagneticFieldZone.Blackout
        horizontalIntensityNanoTesla < MAGNETIC_CAUTION_NANOTESLA -> MagneticFieldZone.Caution
        else -> MagneticFieldZone.Normal
    }
}

/**
 * World Magnetic Model 2025 (NOAA NCEI and the British Geological Survey), bundled
 * because Android's GeomagneticField still ships the 2020 model or older, which has
 * drifted by several degrees in the Arctic.
 *
 * Valid from 2025.0 to 2030.0. Replace [COEFFICIENTS] and [EPOCH_YEAR] with WMM2030
 * when it is published (expected in December 2029).
 */
object WorldMagneticModel {
    const val EPOCH_YEAR = 2025.0

    private const val MAX_DEGREE = 12
    private const val REFERENCE_RADIUS_METERS = 6_371_200.0
    private const val WGS84_SEMI_MAJOR_AXIS_METERS = 6_378_137.0
    private const val WGS84_FLATTENING = 1.0 / 298.257223563

    // Keeps the east component finite at the geographic poles.
    private const val MAX_LATITUDE_DEGREES = 89.999999

    // WMM2025 coefficients, one term per row: n, m, g (nT), h (nT), g rate (nT/yr), h rate (nT/yr).
    private val COEFFICIENTS = doubleArrayOf(
        1.0, 0.0, -29351.8, 0.0, 12.0, 0.0,
        1.0, 1.0, -1410.8, 4545.4, 9.7, -21.5,
        2.0, 0.0, -2556.6, 0.0, -11.6, 0.0,
        2.0, 1.0, 2951.1, -3133.6, -5.2, -27.7,
        2.0, 2.0, 1649.3, -815.1, -8.0, -12.1,
        3.0, 0.0, 1361.0, 0.0, -1.3, 0.0,
        3.0, 1.0, -2404.1, -56.6, -4.2, 4.0,
        3.0, 2.0, 1243.8, 237.5, 0.4, -0.3,
        3.0, 3.0, 453.6, -549.5, -15.6, -4.1,
        4.0, 0.0, 895.0, 0.0, -1.6, 0.0,
        4.0, 1.0, 799.5, 278.6, -2.4, -1.1,
        4.0, 2.0, 55.7, -133.9, -6.0, 4.1,
        4.0, 3.0, -281.1, 212.0, 5.6, 1.6,
        4.0, 4.0, 12.1, -375.6, -7.0, -4.4,
        5.0, 0.0, -233.2, 0.0, 0.6, 0.0,
        5.0, 1.0, 368.9, 45.4, 1.4, -0.5,
        5.0, 2.0, 187.2, 220.2, 0.0, 2.2,
        5.0, 3.0, -138.7, -122.9, 0.6, 0.4,
        5.0, 4.0, -142.0, 43.0, 2.2, 1.7,
        5.0, 5.0, 20.9, 106.1, 0.9, 1.9,
        6.0, 0.0, 64.4, 0.0, -0.2, 0.0,
        6.0, 1.0, 63.8, -18.4, -0.4, 0.3,
        6.0, 2.0, 76.9, 16.8, 0.9, -1.6,
        6.0, 3.0, -115.7, 48.8, 1.2, -0.4,
        6.0, 4.0, -40.9, -59.8, -0.9, 0.9,
        6.0, 5.0, 14.9, 10.9, 0.3, 0.7,
        6.0, 6.0, -60.7, 72.7, 0.9, 0.9,
        7.0, 0.0, 79.5, 0.0, -0.0, 0.0,
        7.0, 1.0, -77.0, -48.9, -0.1, 0.6,
        7.0, 2.0, -8.8, -14.4, -0.1, 0.5,
        7.0, 3.0, 59.3, -1.0, 0.5, -0.8,
        7.0, 4.0, 15.8, 23.4, -0.1, 0.0,
        7.0, 5.0, 2.5, -7.4, -0.8, -1.0,
        7.0, 6.0, -11.1, -25.1, -0.8, 0.6,
        7.0, 7.0, 14.2, -2.3, 0.8, -0.2,
        8.0, 0.0, 23.2, 0.0, -0.1, 0.0,
        8.0, 1.0, 10.8, 7.1, 0.2, -0.2,
        8.0, 2.0, -17.5, -12.6, 0.0, 0.5,
        8.0, 3.0, 2.0, 11.4, 0.5, -0.4,
        8.0, 4.0, -21.7, -9.7, -0.1, 0.4,
        8.0, 5.0, 16.9, 12.7, 0.3, -0.5,
        8.0, 6.0, 15.0, 0.7, 0.2, -0.6,
        8.0, 7.0, -16.8, -5.2, -0.0, 0.3,
        8.0, 8.0, 0.9, 3.9, 0.2, 0.2,
        9.0, 0.0, 4.6, 0.0, -0.0, 0.0,
        9.0, 1.0, 7.8, -24.8, -0.1, -0.3,
        9.0, 2.0, 3.0, 12.2, 0.1, 0.3,
        9.0, 3.0, -0.2, 8.3, 0.3, -0.3,
        9.0, 4.0, -2.5, -3.3, -0.3, 0.3,
        9.0, 5.0, -13.1, -5.2, 0.0, 0.2,
        9.0, 6.0, 2.4, 7.2, 0.3, -0.1,
        9.0, 7.0, 8.6, -0.6, -0.1, -0.2,
        9.0, 8.0, -8.7, 0.8, 0.1, 0.4,
        9.0, 9.0, -12.9, 10.0, -0.1, 0.1,
        10.0, 0.0, -1.3, 0.0, 0.1, 0.0,
        10.0, 1.0, -6.4, 3.3, 0.0, 0.0,
        10.0, 2.0, 0.2, 0.0, 0.1, -0.0,
        10.0, 3.0, 2.0, 2.4, 0.1, -0.2,
        10.0, 4.0, -1.0, 5.3, -0.0, 0.1,
        10.0, 5.0, -0.6, -9.1, -0.3, -0.1,
        10.0, 6.0, -0.9, 0.4, 0.0, 0.1,
        10.0, 7.0, 1.5, -4.2, -0.1, 0.0,
        10.0, 8.0, 0.9, -3.8, -0.1, -0.1,
        10.0, 9.0, -2.7, 0.9, -0.0, 0.2,
        10.0, 10.0, -3.9, -9.1, -0.0, -0.0,
        11.0, 0.0, 2.9, 0.0, 0.0, 0.0,
        11.0, 1.0, -1.5, 0.0, -0.0, -0.0,
        11.0, 2.0, -2.5, 2.9, 0.0, 0.1,
        11.0, 3.0, 2.4, -0.6, 0.0, -0.0,
        11.0, 4.0, -0.6, 0.2, 0.0, 0.1,
        11.0, 5.0, -0.1, 0.5, -0.1, -0.0,
        11.0, 6.0, -0.6, -0.3, 0.0, -0.0,
        11.0, 7.0, -0.1, -1.2, -0.0, 0.1,
        11.0, 8.0, 1.1, -1.7, -0.1, -0.0,
        11.0, 9.0, -1.0, -2.9, -0.1, 0.0,
        11.0, 10.0, -0.2, -1.8, -0.1, 0.0,
        11.0, 11.0, 2.6, -2.3, -0.1, 0.0,
        12.0, 0.0, -2.0, 0.0, 0.0, 0.0,
        12.0, 1.0, -0.2, -1.3, 0.0, -0.0,
        12.0, 2.0, 0.3, 0.7, -0.0, 0.0,
        12.0, 3.0, 1.2, 1.0, -0.0, -0.1,
        12.0, 4.0, -1.3, -1.4, -0.0, 0.1,
        12.0, 5.0, 0.6, -0.0, -0.0, -0.0,
        12.0, 6.0, 0.6, 0.6, 0.1, -0.0,
        12.0, 7.0, 0.5, -0.1, -0.0, -0.0,
        12.0, 8.0, -0.1, 0.8, 0.0, 0.0,
        12.0, 9.0, -0.4, 0.1, 0.0, -0.0,
        12.0, 10.0, -0.2, -1.0, -0.1, -0.0,
        12.0, 11.0, -1.3, 0.1, -0.0, 0.0,
        12.0, 12.0, -0.7, 0.2, -0.1, -0.1,
    )

    private val g = Array(MAX_DEGREE + 1) { DoubleArray(MAX_DEGREE + 1) }
    private val h = Array(MAX_DEGREE + 1) { DoubleArray(MAX_DEGREE + 1) }
    private val gRate = Array(MAX_DEGREE + 1) { DoubleArray(MAX_DEGREE + 1) }
    private val hRate = Array(MAX_DEGREE + 1) { DoubleArray(MAX_DEGREE + 1) }

    // Converts Gauss-normalized Legendre functions to the Schmidt semi-normalized ones the model uses.
    private val schmidtFactors = Array(MAX_DEGREE + 1) { DoubleArray(MAX_DEGREE + 1) }

    init {
        for (row in COEFFICIENTS.indices step 6) {
            val n = COEFFICIENTS[row].toInt()
            val m = COEFFICIENTS[row + 1].toInt()
            g[n][m] = COEFFICIENTS[row + 2]
            h[n][m] = COEFFICIENTS[row + 3]
            gRate[n][m] = COEFFICIENTS[row + 4]
            hRate[n][m] = COEFFICIENTS[row + 5]
        }
        schmidtFactors[0][0] = 1.0
        for (n in 1..MAX_DEGREE) {
            schmidtFactors[n][0] = schmidtFactors[n - 1][0] * (2 * n - 1) / n
            for (m in 1..n) {
                val numerator = (n - m + 1) * (if (m == 1) 2 else 1)
                schmidtFactors[n][m] = schmidtFactors[n][m - 1] * sqrt(numerator.toDouble() / (n + m))
            }
        }
    }

    fun fieldAt(
        latitudeDegrees: Double,
        longitudeDegrees: Double,
        altitudeMeters: Double,
        timeMillis: Long,
    ): GeomagneticFieldValues {
        return fieldAt(latitudeDegrees, longitudeDegrees, altitudeMeters, decimalYear(timeMillis))
    }

    fun fieldAt(
        latitudeDegrees: Double,
        longitudeDegrees: Double,
        altitudeMeters: Double,
        decimalYear: Double,
    ): GeomagneticFieldValues {
        val yearsSinceEpoch = decimalYear - EPOCH_YEAR
        val latitude = Math.toRadians(latitudeDegrees.coerceIn(-MAX_LATITUDE_DEGREES, MAX_LATITUDE_DEGREES))
        val longitude = Math.toRadians(longitudeDegrees)

        // WGS84 geodetic position to geocentric spherical coordinates.
        val eccentricitySquared = WGS84_FLATTENING * (2 - WGS84_FLATTENING)
        val sinLatitude = sin(latitude)
        val cosLatitude = cos(latitude)
        val primeVerticalRadius = WGS84_SEMI_MAJOR_AXIS_METERS /
            sqrt(1 - eccentricitySquared * sinLatitude * sinLatitude)
        val distanceFromAxis = (primeVerticalRadius + altitudeMeters) * cosLatitude
        val heightAboveEquator = (primeVerticalRadius * (1 - eccentricitySquared) + altitudeMeters) * sinLatitude
        val radius = hypot(distanceFromAxis, heightAboveEquator)
        val geocentricLatitude = asin(heightAboveEquator / radius)
        // Theta is the geocentric colatitude.
        val cosTheta = sin(geocentricLatitude)
        val sinTheta = cos(geocentricLatitude)

        // Gauss-normalized associated Legendre functions and their derivatives with respect to theta.
        val legendre = Array(MAX_DEGREE + 1) { DoubleArray(MAX_DEGREE + 1) }
        val legendreDerivative = Array(MAX_DEGREE + 1) { DoubleArray(MAX_DEGREE + 1) }
        legendre[0][0] = 1.0
        for (n in 1..MAX_DEGREE) {
            for (m in 0..n) {
                if (m == n) {
                    legendre[n][n] = sinTheta * legendre[n - 1][n - 1]
                    legendreDerivative[n][n] = sinTheta * legendreDerivative[n - 1][n - 1] +
                        cosTheta * legendre[n - 1][n - 1]
                } else {
                    val k = if (n > 1) {
                        ((n - 1) * (n - 1) - m * m).toDouble() / ((2 * n - 1) * (2 * n - 3))
                    } else {
                        0.0
                    }
                    val twoDegreesBelow = if (m <= n - 2) legendre[n - 2][m] else 0.0
                    val twoDegreesBelowDerivative = if (m <= n - 2) legendreDerivative[n - 2][m] else 0.0
                    legendre[n][m] = cosTheta * legendre[n - 1][m] - k * twoDegreesBelow
                    legendreDerivative[n][m] = cosTheta * legendreDerivative[n - 1][m] -
                        sinTheta * legendre[n - 1][m] - k * twoDegreesBelowDerivative
                }
            }
        }

        var north = 0.0
        var east = 0.0
        var down = 0.0
        for (n in 1..MAX_DEGREE) {
            val radiusRatio = (REFERENCE_RADIUS_METERS / radius).pow(n + 2)
            for (m in 0..n) {
                val gnm = g[n][m] + yearsSinceEpoch * gRate[n][m]
                val hnm = h[n][m] + yearsSinceEpoch * hRate[n][m]
                val cosMLongitude = cos(m * longitude)
                val sinMLongitude = sin(m * longitude)
                val schmidtLegendre = schmidtFactors[n][m] * legendre[n][m]
                val schmidtDerivative = schmidtFactors[n][m] * legendreDerivative[n][m]
                val inPhase = gnm * cosMLongitude + hnm * sinMLongitude
                north += radiusRatio * inPhase * schmidtDerivative
                east += radiusRatio * m * (gnm * sinMLongitude - hnm * cosMLongitude) * schmidtLegendre / sinTheta
                down -= (n + 1) * radiusRatio * inPhase * schmidtLegendre
            }
        }

        // Rotate from the geocentric frame into the local geodetic one.
        val latitudeDifference = latitude - geocentricLatitude
        val geodeticNorth = north * cos(latitudeDifference) + down * sin(latitudeDifference)
        val geodeticDown = -north * sin(latitudeDifference) + down * cos(latitudeDifference)
        val horizontal = hypot(geodeticNorth, east)
        return GeomagneticFieldValues(
            declinationDegrees = Math.toDegrees(atan2(east, geodeticNorth)),
            inclinationDegrees = Math.toDegrees(atan2(geodeticDown, horizontal)),
            horizontalIntensityNanoTesla = horizontal,
            totalIntensityNanoTesla = hypot(horizontal, geodeticDown),
        )
    }

    /** Calendar year plus the elapsed fraction of it, in UTC, as the model expects. */
    internal fun decimalYear(timeMillis: Long): Double {
        val year = Instant.ofEpochMilli(timeMillis).atZone(ZoneOffset.UTC).year
        val yearStart = LocalDate.of(year, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val nextYearStart = LocalDate.of(year + 1, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        return year + (timeMillis - yearStart).toDouble() / (nextYearStart - yearStart)
    }
}
