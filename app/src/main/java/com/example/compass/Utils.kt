package com.example.compass

import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.DetectedActivity

class Utils {
    companion object{
        public fun toActivityString(activity: Int): String? {
            return when (activity) {
                DetectedActivity.STILL -> "STILL"
                DetectedActivity.WALKING -> "WALKING"
                DetectedActivity.ON_BICYCLE -> "ON_BICYCLE"
                DetectedActivity.IN_VEHICLE -> "IN_VEHICLE"
                DetectedActivity.RUNNING -> "RUNNING"
                else -> "UNKNOWN"
            }
        }

        public fun toTransitionType(transitionType: Int): String? {
            return when (transitionType) {
                ActivityTransition.ACTIVITY_TRANSITION_ENTER -> "ENTER"
                ActivityTransition.ACTIVITY_TRANSITION_EXIT -> "EXIT"
                else -> "UNKNOWN"
            }
        }

    }
}