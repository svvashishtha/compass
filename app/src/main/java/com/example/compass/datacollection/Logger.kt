package com.example.compass.datacollection

import android.util.Log
import timber.log.Timber

class Logger {
    companion object {
        fun log(tag: String, message: String) {
            Timber.d(message)
            Log.d(tag, message)
        }

        fun eLog(tag: String, message: String) {
            Timber.e(message)
            Log.e(tag, message)
        }

    }
}