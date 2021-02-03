package com.example.compass

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.hardware.*
import android.hardware.SensorManager.*
import android.os.Bundle
import android.util.Log
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import java.util.*


class MainActivity : AppCompatActivity(), SensorEventListener {
    private var textViewMagneticDeclination: TextView? = null
    private var textViewHeading: TextView? = null
    private var textViewTrueHeading: TextView? = null
    private var imageViewCompass: ImageView? = null
    private var trueHeading: Float = 0f
    private var oldHeading: Float = 0f
    private var heading: Float = 0f
    private var magneticDeclination: Float = 0f
    private var altitude: Float = 0f
    private var latitude: Float = 0f
    private var longitude: Float = 0f
    private var isLocationRetrieved: Boolean = false
    private val REQUEST_PERMISSION_FINE_LOCATION: Int = 203
    private var fusedLocationClient: FusedLocationProviderClient? = null

    // device sensor manager
    private var sensorManager: SensorManager? = null

    private val accelerometerReading = FloatArray(3)
    private val magnetometerReading = FloatArray(3)


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        imageViewCompass = findViewById(R.id.image_compass)
        textViewTrueHeading = findViewById(R.id.text_view_true_heading)
        textViewHeading = findViewById(R.id.text_view_heading)
        textViewMagneticDeclination = findViewById(R.id.text_view_magnetic_declination)

        // initialize your android device sensor capabilities
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        //check if we have permission to access location
        if (ContextCompat.checkSelfPermission(
                        this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED) {
            //fine location permission already granted
            getLocation()
        } else {
            //if permission is not granted, request location permissions from user
            ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    REQUEST_PERMISSION_FINE_LOCATION)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSION_FINE_LOCATION) {
            //if request is cancelled, the result arrays are empty.
            if (grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                //permission is granted
                getLocation()
            } else {
                //display Toast with error message
                Toast.makeText(this, R.string.location_error_msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun getLocation() {
        fusedLocationClient?.lastLocation
                ?.addOnSuccessListener(this) { location -> // Got last known location. In some rare situations this can be null.
                    if (location != null) {
                        isLocationRetrieved = true
                        latitude = location.latitude.toFloat()
                        longitude = location.longitude.toFloat()
                        altitude = location.altitude.toFloat()
                        magneticDeclination =
                            CompassHelper.calculateMagneticDeclination(latitude, longitude, altitude)
                        textViewMagneticDeclination?.text = getString(R.string.magnetic_declination, magneticDeclination)
                    }
                }
    }


    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        when(accuracy)
        {
            SENSOR_STATUS_ACCURACY_HIGH ->
                Log.d(TAG, sensor.toString() + "Accuracy changed : SENSOR_STATUS_ACCURACY_HIGH")
            SENSOR_STATUS_ACCURACY_MEDIUM ->
                Log.d(TAG, sensor.toString() + "Accuracy changed : SENSOR_STATUS_ACCURACY_MEDIUM")
            SENSOR_STATUS_ACCURACY_LOW ->
                Log.d(TAG, sensor.toString() + "Accuracy changed : SENSOR_STATUS_ACCURACY_LOW")
             SENSOR_STATUS_NO_CONTACT ->
                Log.d(TAG, sensor.toString() + "Accuracy changed : SENSOR_STATUS_NO_CONTACT")
            SENSOR_STATUS_UNRELIABLE ->
                Log.d(TAG, sensor.toString() + "Accuracy changed : SENSOR_STATUS_UNRELIABLE")
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            //make sensor readings smoother using a low pass filter
            lowPassFilter(event.values.clone(), accelerometerReading)
        } else if (event?.sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) {
            //make sensor readings smoother using a low pass filter
            lowPassFilter(event.values.clone(), magnetometerReading)
        }
        updateHeading()

    }


    //0 ≤ ALPHA ≤ 1
    //smaller ALPHA results in smoother sensor data but slower updates
    private val ALPHA = 0.15f

    private fun lowPassFilter(input: FloatArray, output: FloatArray?): FloatArray? {
        if (output == null) return input
        for (i in input.indices) {
            output[i] = output[i] + ALPHA * (input[i] - output[i])
        }
        return output
    }

    override fun onResume() {
        super.onResume()
        val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelerometer != null) {
            sensorManager?.registerListener(this, accelerometer,
                    SENSOR_DELAY_GAME, SENSOR_DELAY_GAME)
        }

        val magneticField: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (magneticField != null) {
            sensorManager?.registerListener(this, magneticField,
                    SENSOR_DELAY_GAME, SENSOR_DELAY_GAME)
        }

    }

    private fun updateHeading() {
        //oldHeading required for image rotate animation
        oldHeading = heading
        heading = CompassHelper.calculateHeading(accelerometerReading, magnetometerReading)
        heading = CompassHelper.convertRadtoDeg(heading)
        heading = CompassHelper.map180to360(heading)
        if (isLocationRetrieved) {
            trueHeading = heading + magneticDeclination
            if (trueHeading > 360) { //if trueHeading was 362 degrees for example, it should be adjusted to be 2 degrees instead
                trueHeading -= 360
            }
            textViewTrueHeading?.text = getString(R.string.true_heading, trueHeading.toInt())
        }
        textViewHeading?.text = getString(R.string.heading, heading.toInt())
        val rotateAnimation = RotateAnimation(-oldHeading, -heading, Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f)
        rotateAnimation.duration = 500
        rotateAnimation.fillAfter = true
        imageViewCompass?.startAnimation(rotateAnimation)
    }

    override fun onPause() {
        super.onPause()

        sensorManager?.unregisterListener(this)
    }

    companion object {
        const val TAG = "Sensor_MainActivity"
    }

}