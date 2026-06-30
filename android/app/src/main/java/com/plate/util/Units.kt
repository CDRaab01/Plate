package com.plate.util

/**
 * Unit-system display preference + the lb↔kg / oz↔g conversions, mirroring the server's
 * `app/nutrition/units.py`. Storage is canonical metric; these convert at the UI edge for display
 * and input. Default is [UnitSystem.IMPERIAL] (the server's default for a new user).
 */
enum class UnitSystem(val wire: String, val weightUnit: String, val foodUnit: String) {
    IMPERIAL("imperial", "lb", "oz"),
    METRIC("metric", "kg", "g"),
    ;

    companion object {
        /** Parse the server's `unit_system` string, defaulting to imperial for anything unknown. */
        fun fromWire(value: String?): UnitSystem =
            entries.firstOrNull { it.wire == value } ?: IMPERIAL
    }
}

object Units {
    // Exact NIST factors, matching the server.
    const val KG_PER_LB = 0.45359237
    const val G_PER_OZ = 28.349523125

    fun lbToKg(lb: Double) = lb * KG_PER_LB
    fun kgToLb(kg: Double) = kg / KG_PER_LB
    fun ozToG(oz: Double) = oz * G_PER_OZ
    fun gToOz(g: Double) = g / G_PER_OZ
}
