package com.tunisianprayertimes.engine

import com.tunisianprayertimes.place.OfficialZone
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.tan
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * E2: the sun's altitude at sunrise or sunset (plan 6.2). Twilight angles never take a dip (E3).
 */
@Serializable
sealed interface HorizonModel {
    /** −0.8333°: 16′ of semi-diameter and 34′ of refraction, no dip. Every European preset. */
    @Serializable
    @SerialName("standard")
    data object Standard : HorizonModel

    /** A fixed altitude: `sa_umm_al_qura`'s sunrise −0.83°, `apbif`'s −1°, `ae_awqaf`'s fallback −1.4°. */
    @Serializable
    @SerialName("fixed")
    data class Fixed(val altitudeDeg: Double) : HorizonModel

    /** Yemen: −(0.2667° + 0.5667° e^(−h/8434 m)) at the location's elevation h. */
    @Serializable
    @SerialName("pressure")
    data object Pressure : HorizonModel

    /**
     * Accurate Times (Jordan, the UAE, Iraq): the semi-diameter, Bennett's refraction scaled by the
     * zone's pressure and temperature, and the geometric dip of its elevation. At exact coordinates
     * the [fallback] applies; with none, the place's own elevation at 1010 hPa and 10 °C.
     */
    @Serializable
    @SerialName("refraction")
    data class Refraction(val fallback: HorizonModel? = null) : HorizonModel

    /** Morocco: the zone's fitted sunrise and sunset altitudes; the [fallback] at exact coordinates. */
    @Serializable
    @SerialName("per_zone")
    data class PerZone(val fallback: HorizonModel) : HorizonModel

    /**
     * Morocco at exact coordinates (8.2): −([baseDeg] + [perSqrtMetreDeg]·√h) at the elevation h (0 when unknown),
     * the law the Habous localities' fitted horizons follow (fits/ma_habous).
     */
    @Serializable
    @SerialName("sqrt_height")
    data class SqrtHeight(val baseDeg: Double, val perSqrtMetreDeg: Double) : HorizonModel

    /** «مخصّصة»'s «ارتفاع المكان» (6.11): −0.8333° less the geometric dip of the place's elevation. */
    @Serializable
    @SerialName("standard_with_dip")
    data object StandardWithDip : HorizonModel
}

/** E2: the horizon models of sunrise and of sunset, which may differ (Morocco, Saudi Arabia, Jordan). */
@Serializable
data class Horizons(val sunrise: HorizonModel, val sunset: HorizonModel) {
    companion object {
        val STANDARD = Horizons(HorizonModel.Standard, HorizonModel.Standard)
    }
}

/** The inputs a horizon model reads: the elevation, air and zone of the event's side of the day. */
internal data class HorizonInputs(
    val elevationM: Double,
    val pressureHpa: Double,
    val temperatureC: Double,
    val zone: OfficialZone?,
    val distanceAu: Double,
)

internal object HorizonAltitude {
    private const val DEG = PI / 180
    const val STANDARD_DEG = -0.8333
    private const val EARTH_RADIUS_M = 6378137.0

    /** The altitude in degrees of [model] for a sunrise ([rising]) or a sunset. */
    fun of(model: HorizonModel, rising: Boolean, inputs: HorizonInputs): Double = when (model) {
        HorizonModel.Standard -> STANDARD_DEG
        is HorizonModel.Fixed -> model.altitudeDeg
        HorizonModel.Pressure -> -(0.2667 + 0.5667 * exp(-inputs.elevationM / 8434.0))
        is HorizonModel.Refraction ->
            if (inputs.zone == null && model.fallback != null) of(model.fallback, rising, inputs)
            else refracted(inputs)
        is HorizonModel.PerZone -> {
            val zoneAltitude = if (rising) inputs.zone?.sunriseAltitudeDeg else inputs.zone?.sunsetAltitudeDeg
            zoneAltitude ?: of(model.fallback, rising, inputs)
        }
        HorizonModel.StandardWithDip -> STANDARD_DEG - geometricDipDeg(inputs.elevationM)
        is HorizonModel.SqrtHeight -> -(model.baseDeg + model.perSqrtMetreDeg * sqrt(inputs.elevationM.coerceAtLeast(0.0)))
    }

    /** −(semi-diameter + refraction at the dipped horizon + dip), as Accurate Times computes (8.6). */
    private fun refracted(inputs: HorizonInputs): Double {
        val semiDiameter = 959.63 / 3600.0 / inputs.distanceAu
        val dip = geometricDipDeg(inputs.elevationM)
        val refraction = bennettRefractionDeg(-dip, inputs.pressureHpa, inputs.temperatureC)
        return -(semiDiameter + refraction + dip)
    }

    /** The dip of the sea horizon seen from [elevationM], acos(R / (R + h)), with no terrestrial refraction. */
    fun geometricDipDeg(elevationM: Double): Double =
        if (elevationM <= 0) 0.0 else acos(EARTH_RADIUS_M / (EARTH_RADIUS_M + elevationM)) / DEG

    /** Bennett's refraction for an apparent altitude, scaled by pressure and temperature (Meeus 16.4). */
    fun bennettRefractionDeg(apparentAltitudeDeg: Double, pressureHpa: Double, temperatureC: Double): Double {
        val a = apparentAltitudeDeg
        val arcMinutes = 1.0 / tan((a + 7.31 / (a + 4.4)) * DEG)
        return arcMinutes / 60.0 * (pressureHpa / 1010.0) * (283.0 / (273.0 + temperatureC))
    }
}
