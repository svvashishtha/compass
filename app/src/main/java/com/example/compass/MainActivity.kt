package com.example.compass

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.SensorManager.*
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.compass.databinding.ActivityMainBinding
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.*


class MainActivity : AppCompatActivity(), SensorEventListener {
    private val activityTransitionIntentAction: String =
        "TRANSITIONS_RECEIVER_ACTION"
    private var trueHeading: Double = 0.0
    private var locationAccuracy: Double = 0.0
    private var oldHeading: Double = 0.0
    private var heading: Double = 0.0
    private var speed: Double = 0.0
    private var loicationTime: Long = 0
    private var magneticDeclination: Double = 0.0
    private var altitude: Double = 0.0
    private var latitude: Double = 0.0
    private var longitude: Double = 0.0
    private var isLocationRetrieved: Boolean = false
    private val REQUEST_PERMISSION_FINE_LOCATION: Int = 203
    private val REQUEST_PERMISSION_ACTIVTY_RECOGNITION: Int = 204
    private val REQUEST_ENABLE_LOCATION: Int = 205
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var binding: ActivityMainBinding? = null
    private val transitions = mutableListOf<ActivityTransition>()
    private val runningQOrLater = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    private var transitionEnabled = false
    private var acceleroMeterAccuracy: String = "Unknown"
    private var gyroScopeAccuracy: String = "Unknown"
    private var magnetoMeterAccuracy: String = "Unknown"
    var handler: Handler = Handler(Looper.getMainLooper())

    // device sensor manager
    private var sensorManager: SensorManager? = null

    private val accelerometerReading = FloatArray(3)
    private val magnetometerReading = FloatArray(3)
    private val gyroScopeReading = FloatArray(3)
    var pattern = " HH:mm:ss.SSS"
    var lastActivity: String = "Unknown"
    var currentActivity: String = "Unknown"
    var simpleDateFormat: SimpleDateFormat = SimpleDateFormat(pattern)
    private val activityTransitionBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ActivityTransitionResult.hasResult(intent)) {
                val result = ActivityTransitionResult.extractResult(intent)!!
                Logger.log(TAG, "Received broadcast with activity recognition result = $result")
                for (event in result.transitionEvents) {
                    when (event.transitionType) {
                        ActivityTransition.ACTIVITY_TRANSITION_EXIT -> {
                            binding?.activityExit?.text = String.format(
                                "%2s : %1s",
                                Utils.toActivityString(event.activityType),
                                Utils.toTransitionType(event.transitionType)
                            )
                            lastActivity = Utils.toActivityString(event.activityType) ?: "Unknown"
                        }
                        ActivityTransition.ACTIVITY_TRANSITION_ENTER -> {
                            binding?.activityEntry?.text = String.format(
                                "%2s : %1s",
                                Utils.toActivityString(event.activityType),
                                Utils.toTransitionType(event.transitionType)
                            )
                            currentActivity =
                                Utils.toActivityString(event.activityType) ?: "Unknown"
                        }
                    }
                }

            } else {
                Logger.log(TAG, "Received broadcast with no activity recognition result")
            }
        }

    }
    private val locationCallback: LocationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult?) {
            if (locationResult == null) return
            locationResult.lastLocation.let { location -> // Got last known location. In some rare situations this can be null.
                isLocationRetrieved = true
                latitude = location.latitude
                longitude = location.longitude
                altitude = location.altitude
                speed = location.speed.toDouble()
                loicationTime = location.time
                locationAccuracy = location.accuracy.toDouble()
                binding?.altitude?.text = getString(R.string.altitude, altitude)
                binding?.latitude?.text = getString(R.string.latitude, latitude)
                binding?.longitude?.text = getString(R.string.longitude, longitude)
                binding?.speed?.text = getString(R.string.speed, location.speed)
                binding?.accuracy?.text = getString(R.string.accuracy, location.accuracy)
                binding?.time?.text =
                    getString(R.string.time, simpleDateFormat.format(Date(location.time)))
                magneticDeclination =
                    CompassHelper.calculateMagneticDeclination(latitude, longitude, altitude)
                binding?.textViewMagneticDeclination?.text =
                    getString(R.string.magnetic_declination, magneticDeclination)
            }
        }

        override fun onLocationAvailability(locationAvailability: LocationAvailability?) {}
    }
    val format = Json { allowSpecialFloatingPointValues = true }
    private val loggingRunnable: Runnable = object : Runnable {
        override fun run() {
            // Do something here on the main thread
            logCurrentData()
            // Repeat this the same runnable code block again another 2 seconds
            // 'this' is referencing the Runnable object
            handler.postDelayed(this, 500)
        }
    }
    private var myPendingIntent: PendingIntent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)

        setContentView(binding?.root!!)

        // initialize your android device sensor capabilities
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)


        requestRequiredPermissions()
        registerReceiver(
            activityTransitionBroadcastReceiver,
            IntentFilter(activityTransitionIntentAction)
        )
        handler.post(loggingRunnable)

    }


    private fun requestRequiredPermissions() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED ||
            checkActivityRecognitionPermission() ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                PermissionUtils.PERMISSIONS,
                PermissionUtils.PERMISSIONS_REQUEST_CODE
            )
        } else {
            Timber.plant(FileLoggingTree(this))
            getLocation()
            setUpActivityTransition()
        }


    }

    fun requestActivityRecognitionPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {//check if we have permission to register for activity recognition events

            if (checkActivityRecognitionPermission()) {
                //fine location permission already granted
                setUpActivityTransition()
            } else {
                //if permission is not granted, request location permissions from user
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACTIVITY_RECOGNITION),
                    REQUEST_PERMISSION_ACTIVTY_RECOGNITION
                )
            }
        }
    }

    fun requestLocationPermission() {
        //check if we have permission to access location
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            //fine location permission already granted
            getLocation()
        } else {
            //if permission is not granted, request location permissions from user
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_PERMISSION_FINE_LOCATION
            )
        }
    }

    @SuppressLint("InlinedApi")
    fun checkActivityRecognitionPermission(): Boolean {
        return if (runningQOrLater) {
            PackageManager.PERMISSION_GRANTED != ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACTIVITY_RECOGNITION
            );
        } else {
            true;
        }

    }

    private fun setUpActivityTransition() {
        if (!transitionEnabled) {
            addTransition(DetectedActivity.IN_VEHICLE)
            addTransition(DetectedActivity.ON_BICYCLE)
            addTransition(DetectedActivity.WALKING)
            addTransition(DetectedActivity.ON_FOOT)
            addTransition(DetectedActivity.STILL)
            addTransition(DetectedActivity.RUNNING)
            val request = ActivityTransitionRequest(transitions)
// myPendingIntent is the instance of PendingIntent where the app receives callbacks.
            myPendingIntent = PendingIntent.getBroadcast(
                this,
                0,
                Intent(activityTransitionIntentAction),
                0
            )
            myPendingIntent?.let {
                val task = ActivityRecognition.getClient(this)
                    .requestActivityTransitionUpdates(request, it)

                task.addOnSuccessListener {
                    // Handle success
                    Logger.log(TAG, "Activity recognition update registration success")
                    transitionEnabled = true
                }

                task.addOnFailureListener { e: Exception ->
                    Logger.log(TAG, "Activity recognition update registration failed")
                    Logger.log(TAG, e.toString())
                }
            }
        }
    }

    private fun addTransition(activityType: Int) {
        transitions.add(
            ActivityTransition.Builder()
                .setActivityType(activityType)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build()
        )
        transitions.add(
            ActivityTransition.Builder()
                .setActivityType(activityType)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                .build()
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSION_FINE_LOCATION) {
            //if request is cancelled, the result arrays are empty.
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                //permission is granted
                getLocation()
            } else {
                //display Toast with error message
                Toast.makeText(this, R.string.location_error_msg, Toast.LENGTH_LONG).show()
            }
        }
        if (requestCode == REQUEST_PERMISSION_ACTIVTY_RECOGNITION) {
            //if request is cancelled, the result arrays are empty.
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                //permission is granted
                setUpActivityTransition()
            } else {
                //display Toast with error message
                Toast.makeText(
                    this,
                    getString(R.string.activity_recog_permission_deneid),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        if (requestCode == PermissionUtils.PERMISSIONS_REQUEST_CODE) {
            permissions.forEachIndexed { index, permission ->
                if (permission == Manifest.permission.ACCESS_FINE_LOCATION) {
                    if (grantResults[index] == PackageManager.PERMISSION_GRANTED) {
                        getLocation()
                    } else {
                        Toast.makeText(this, R.string.location_permission_deneid, Toast.LENGTH_LONG)
                            .show()
                    }
                }
                if (permission == Manifest.permission.ACTIVITY_RECOGNITION) {
                    if (grantResults[index] == PackageManager.PERMISSION_GRANTED) {
                        setUpActivityTransition()
                    } else {
                        Toast.makeText(
                            this,
                            R.string.activity_recog_permission_deneid,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                if (permission == Manifest.permission.WRITE_EXTERNAL_STORAGE) {
                    if (grantResults[index] == PackageManager.PERMISSION_GRANTED) {
                        Timber.plant(FileLoggingTree(this))
                    } else {
                        Toast.makeText(
                            this,
                            R.string.storage_permission_deneid,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun getLocation() {
        val locationRequest = LocationRequest.create().apply {
            interval = 10000
            fastestInterval = 5000
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }
        val builder = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
        val client: SettingsClient = LocationServices.getSettingsClient(this)
        val task: Task<LocationSettingsResponse> = client.checkLocationSettings(builder.build())
        task.addOnSuccessListener { locationSettingsResponse ->
            // All location settings are satisfied. The client can initialize
            // location requests here.
            // ...
            fusedLocationClient?.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        }

        task.addOnFailureListener { exception ->
            if (exception is ResolvableApiException) {
                // Location settings are not satisfied, but this can be fixed
                // by showing the user a dialog.
                try {
                    // Show the dialog by calling startResolutionForResult(),
                    // and check the result in onActivityResult().
                    exception.startResolutionForResult(
                        this@MainActivity,
                        REQUEST_ENABLE_LOCATION
                    )
                } catch (sendEx: IntentSender.SendIntentException) {
                    // Ignore the error.
                    Logger.eLog(TAG, sendEx.toString())
                }
            }
        }

    }


    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        when (accuracy) {
            SENSOR_STATUS_ACCURACY_HIGH ->
                Logger.log(
                    TAG,
                    sensor.toString() + "Accuracy changed : SENSOR_STATUS_ACCURACY_HIGH"
                )
            SENSOR_STATUS_ACCURACY_MEDIUM ->
                Logger.log(
                    TAG,
                    sensor.toString() + "Accuracy changed : SENSOR_STATUS_ACCURACY_MEDIUM"
                )
            SENSOR_STATUS_ACCURACY_LOW ->
                Logger.log(TAG, sensor.toString() + "Accuracy changed : SENSOR_STATUS_ACCURACY_LOW")
            SENSOR_STATUS_NO_CONTACT ->
                Logger.log(TAG, sensor.toString() + "Accuracy changed : SENSOR_STATUS_NO_CONTACT")
            SENSOR_STATUS_UNRELIABLE ->
                Logger.log(TAG, sensor.toString() + "Accuracy changed : SENSOR_STATUS_UNRELIABLE")
        }
        when (sensor?.type) {
            null -> {

            }
            Sensor.TYPE_ACCELEROMETER -> {
                binding?.accelerometerAccuracy?.text =
                    getString(R.string.accelerometer_accuracy, getAccuracyValue(accuracy))
                acceleroMeterAccuracy = getAccuracyValue(accuracy).toString()
            }
            Sensor.TYPE_GYROSCOPE -> {
                binding?.gyroscopeAccuracy?.text =
                    getString(R.string.gyroscope_accuracy, getAccuracyValue(accuracy))
                gyroScopeAccuracy = getAccuracyValue(accuracy).toString()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                binding?.magnetometerAccuracy?.text =
                    getString(R.string.magnetometer_accuracy, getAccuracyValue(accuracy))
                magnetoMeterAccuracy = getAccuracyValue(accuracy).toString()
            }
        }
    }

    private fun getAccuracyValue(accuracy: Int): CharSequence? {
        when (accuracy) {
            SENSOR_STATUS_ACCURACY_HIGH -> {
                return "High"
            }
            SENSOR_STATUS_ACCURACY_MEDIUM -> {
                return "Medium"
            }
            SENSOR_STATUS_ACCURACY_LOW -> {
                return "Low"
            }
            SENSOR_STATUS_UNRELIABLE -> {
                return "Unreliable"
            }
            SENSOR_STATUS_NO_CONTACT -> {
                return "No contact"
            }
        }
        return "Unknown"
    }

    override fun onSensorChanged(event: SensorEvent?) {
        when (event?.sensor?.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                //make sensor readings smoother using a low pass filter
                lowPassFilter(event.values.clone(), accelerometerReading)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                //make sensor readings smoother using a low pass filter
                lowPassFilter(event.values.clone(), magnetometerReading)
            }
            Sensor.TYPE_GYROSCOPE -> {
                //make sensor readings smoother using a low pass filter
                lowPassFilter(event.values.clone(), gyroScopeReading)
            }
        }
        updateHeading()
        updateUi()

    }

    private fun updateUi() {
        //update accelerometer
        binding?.ax?.text = getString(R.string.ax, accelerometerReading[0])
        binding?.ay?.text = getString(R.string.ay, accelerometerReading[1])
        binding?.az?.text = getString(R.string.az, accelerometerReading[2])
        binding?.gx?.text = getString(R.string.gx, gyroScopeReading[0])
        binding?.gy?.text = getString(R.string.gy, gyroScopeReading[1])
        binding?.gz?.text = getString(R.string.gz, gyroScopeReading[2])
        binding?.mx?.text = getString(R.string.mx, magnetometerReading[0])
        binding?.my?.text = getString(R.string.my, magnetometerReading[1])
        binding?.mz?.text = getString(R.string.mz, magnetometerReading[2])
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
            sensorManager?.registerListener(
                this, accelerometer,
                SENSOR_DELAY_GAME, SENSOR_DELAY_UI
            )
        }

        val magneticField: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (magneticField != null) {
            sensorManager?.registerListener(
                this, magneticField,
                SENSOR_DELAY_GAME, SENSOR_DELAY_UI
            )
        }
        val gyroscopeSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        if (gyroscopeSensor != null) {
            sensorManager?.registerListener(
                this, gyroscopeSensor,
                SENSOR_DELAY_GAME, SENSOR_DELAY_UI
            )
        }


    }

    override fun onStart() {
        super.onStart()

        registerReceiver(
            activityTransitionBroadcastReceiver,
            IntentFilter(activityTransitionIntentAction)
        )

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
            binding?.textViewTrueHeading?.text =
                getString(R.string.true_heading, trueHeading.toInt())
        }
        binding?.textViewHeading?.text = getString(R.string.heading, heading.toInt())
        val rotateAnimation = RotateAnimation(
            -oldHeading.toFloat(),
            -heading.toFloat(),
            Animation.RELATIVE_TO_SELF,
            0.5f,
            Animation.RELATIVE_TO_SELF,
            0.5f
        )
        rotateAnimation.duration = 500
        rotateAnimation.fillAfter = true
        binding?.imageCompass?.startAnimation(rotateAnimation)
    }

    override fun onPause() {
        super.onPause()

        sensorManager?.unregisterListener(this)


    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(activityTransitionBroadcastReceiver)
        removeActivityRecognitionUpdates()
        handler.removeCallbacks(loggingRunnable)
    }

    private fun removeActivityRecognitionUpdates() {
        myPendingIntent?.let { pendingIntent ->
            val task = ActivityRecognition.getClient(this)
                .removeActivityTransitionUpdates(pendingIntent)

            task.addOnSuccessListener {
                pendingIntent.cancel()
                transitionEnabled = false
            }

            task.addOnFailureListener { e: Exception ->
                Logger.log(TAG, "Activity recognition updates de-registration failed")
                Logger.eLog(TAG, e.message ?: "Unknown error")
            }
        }

    }

    private fun getDataObjectFromCurrentValues(): PositionDataObject {
        return PositionDataObject(
            latitude = latitude,
            longitude = longitude,
            altitude = altitude,
            heading = heading,
            speed = speed,
            time = loicationTime,
            declination = magneticDeclination,
            trueHeading = trueHeading,
            accuracy = locationAccuracy,
            acceleroMeterAccuracy = acceleroMeterAccuracy,
            magnetoMeterAccuracy = magnetoMeterAccuracy,
            gyroScopeAccuracy = gyroScopeAccuracy,
            ax = accelerometerReading[0],
            ay = accelerometerReading[1],
            az = accelerometerReading[2],
            mx = magnetometerReading[0],
            my = magnetometerReading[1],
            mz = magnetometerReading[2],
            gx = magnetometerReading[0],
            gy = magnetometerReading[1],
            gz = magnetometerReading[2],
            lastActivity = lastActivity,
            currentActivity = currentActivity
        )
    }

    companion object {
        const val TAG = "Sensor_MainActivity"
    }

    fun logCurrentData() {

        Logger.log(TAG, format.encodeToString(getDataObjectFromCurrentValues()))
    }

}