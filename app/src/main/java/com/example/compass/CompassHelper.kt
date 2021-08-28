package com.example.compass

import android.hardware.GeomagneticField
import kotlin.math.atan2
import kotlin.math.sqrt

class CompassHelper {
    companion object {
        fun calculateHeading(
            accelerometerReading: FloatArray,
            magnetometerReading: FloatArray
        ): Double {
            var Ax = accelerometerReading[0]
            var Ay = accelerometerReading[1]
            var Az = accelerometerReading[2]
            val Ex = magnetometerReading[0]
            val Ey = magnetometerReading[1]
            val Ez = magnetometerReading[2]

            //cross product of the magnetic field vector and the gravity vector
            var Hx = Ey * Az - Ez * Ay
            var Hy = Ez * Ax - Ex * Az
            var Hz = Ex * Ay - Ey * Ax

            //normalize the values of resulting vector
            val invH = 1.0f / sqrt(Hx * Hx + Hy * Hy + (Hz * Hz).toDouble()).toFloat()
            Hx *= invH
            Hy *= invH
            Hz *= invH

            //normalize the values of gravity vector
            val invA = 1.0f / sqrt(Ax * Ax + Ay * Ay + (Az * Az).toDouble()).toFloat()
            Ax *= invA
            Ay *= invA
            Az *= invA

            //cross product of the gravity vector and the new vector H
            val Mx = Ay * Hz - Az * Hy
            val My = Az * Hx - Ax * Hz
            val Mz = Ax * Hy - Ay * Hx

            //arctangent to obtain heading in radians
            return atan2(Hy.toDouble(), My.toDouble())
        }

        fun convertRadtoDeg(rad: Double): Double {
            return (rad / Math.PI) * 180
        }

        //map angle from [-180,180] range to [0,360] range
        fun map180to360(angle: Double): Double {
            return (angle + 360) % 360
        }

        fun calculateMagneticDeclination(
            latitude: Double,
            longitude: Double,
            altitude: Double
        ): Double {
            return GeomagneticField(
                latitude.toFloat(),
                longitude.toFloat(),
                altitude.toFloat(),
                System.currentTimeMillis()
            ).declination.toDouble()
        }
    }
}