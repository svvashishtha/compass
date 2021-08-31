package com.example.compass.datacollection.objects

import kotlinx.serialization.Serializable

@Serializable
data class PositionDataObject(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val speed: Double,
    val accuracy: Double,
    val time: Long,
    val declination: Double,
    val heading: Double,
    val oldHeading: Double,
    val trueHeading: Double,
    val acceleroMeterAccuracy: String,
    val magnetoMeterAccuracy: String,
    val gyroScopeAccuracy: String,
    val ax: Float,
    val ay: Float,
    val az: Float,
    val gx: Float,
    val gy: Float,
    val gz: Float,
    val mx: Float,
    val my: Float,
    val mz: Float,
    val lastActivity: String,
    val currentActivity: String
): java.io.Serializable{
    override fun toString(): String {
        return super.toString()
    }
}