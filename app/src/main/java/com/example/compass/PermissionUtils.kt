package com.example.compass

import android.Manifest
import android.os.Build

class PermissionUtils {
    companion object {
        val PERMISSIONS_REQUEST_CODE: Int = 550
        val PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) Manifest.permission.ACTIVITY_RECOGNITION else "com.google.android.gms.permission.ACTIVITY_RECOGNITION"
        )
    }
}