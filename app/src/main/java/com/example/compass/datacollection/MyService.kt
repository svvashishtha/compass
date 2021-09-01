package com.example.compass.datacollection

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.*
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.MutableLiveData
import com.example.compass.datacollection.helpers.CompassHelper
import com.example.compass.datacollection.objects.PositionDataObject
import com.example.compass.R
import com.example.compass.datacollection.helpers.Utils
import com.example.compass.ui.MainActivity
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.android.gms.tasks.Task
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.*


class MyService : Service(), SensorEventListener {
    companion object {
        val START_SERVICE = "start"
        val STOP_SERVICE = "stop"
        val FOREGROUND_SERVICE = "foreground"
        const val TAG = "MyService"
    }

    var isForeGroundService = false

    val CHANNEL_ID: String = "channelId"

    // device sensor manager
    private val activityTransitionIntentAction: String =
        "TRANSITIONS_RECEIVER_ACTION"
    private var sensorManager: SensorManager? = null
    private var isLocationRetrieved: Boolean = false
    private val accelerometerReading = FloatArray(3)
    private val magnetometerReading = FloatArray(3)
    private val gyroScopeReading = FloatArray(3)
    private var trueHeading: Double = 0.0
    private var oldHeading: Double = 0.0
    private var heading: Double = 0.0
    private var magneticDeclination: Double = 0.0
    private var acceleroMeterAccuracy: String = "Unknown"
    private var gyroScopeAccuracy: String = "Unknown"
    private var magnetoMeterAccuracy: String = "Unknown"
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var altitude: Double = 0.0
    private var latitude: Double = 0.0
    private var longitude: Double = 0.0
    private var speed: Double = 0.0
    private var loicationTime: Long = 0
    private var locationAccuracy: Double = 0.0
    var pattern = " HH:mm:ss.SSS"
    var lastActivity: String = "Unknown"
    var currentActivity: String = "Unknown"
    var simpleDateFormat: SimpleDateFormat = SimpleDateFormat(pattern)
    private val activityTransitionBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ActivityTransitionResult.hasResult(intent)) {
                val result = ActivityTransitionResult.extractResult(intent)!!
                Logger.log(
                    "Received broadcast with activity recognition result = $result"
                )
                for (event in result.transitionEvents) {
                    when (event.transitionType) {
                        ActivityTransition.ACTIVITY_TRANSITION_EXIT -> {
                            lastActivity = Utils.toActivityString(event.activityType) ?: "Unknown"
                        }
                        ActivityTransition.ACTIVITY_TRANSITION_ENTER -> {
                            currentActivity =
                                Utils.toActivityString(event.activityType) ?: "Unknown"
                        }
                    }
                }

            } else {
                Logger.log(
                    "Received broadcast with no activity recognition result"
                )
            }
        }

    }
    private val locationCallback: LocationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult?) {
            if (locationResult == null) return
            locationResult.locations.let { locations -> // Got last known location. In some rare situations this can be null.
                if (locations.size > 0) {
                    locations.last()?.let { location ->
                        isLocationRetrieved = true
                        latitude = location.latitude
                        longitude = location.longitude
                        altitude = location.altitude
                        speed = location.speed.toDouble()
                        loicationTime = location.time
                        locationAccuracy = location.accuracy.toDouble()
                        magneticDeclination =
                            CompassHelper.calculateMagneticDeclination(
                                latitude,
                                longitude,
                                altitude
                            )
                        /**
                         * If service is in foreground then data logging has been requested.
                         * */
                        if (isForeGroundService) {
                            logCurrentData()
                        } else {
                            Logger.log(
                                TAG,
                                format.encodeToString(getDataObjectFromCurrentValues()),
                            )
                        }
                    }
                }
            }
        }

        override fun onLocationAvailability(locationAvailability: LocationAvailability?) {}
    }

    private var transitionEnabled = false
    private var myPendingIntent: PendingIntent? = null
    private val transitions = mutableListOf<ActivityTransition>()
    private val format = Json { allowSpecialFloatingPointValues = true }
    val liveDataPositionObject = MutableLiveData<PositionDataObject>()

    inner class LocalBinder : Binder() {
        fun getService(): MyService = this@MyService
    }

    override fun onCreate() {
        super.onCreate()
        isForeGroundService = false
        // initialize your android device sensor capabilities
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        registerReceiver(
            activityTransitionBroadcastReceiver,
            IntentFilter(activityTransitionIntentAction)
        )
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent): IBinder {
        Log.d(TAG, "onBind")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (!isForeGroundService) {
            stopService()
        }
        return super.onUnbind(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val intentAction = intent?.action
        when (intentAction) {
            START_SERVICE -> {
                showToast("Service started")
                registerAsSensorListener()
                getLocation()
                setUpActivityTransition()
            }
            STOP_SERVICE -> {
                stopService()
                sensorManager?.unregisterListener(this)
                removeActivityRecognitionUpdates()
                fusedLocationClient?.removeLocationUpdates(locationCallback)
                unregisterReceiver(activityTransitionBroadcastReceiver)
            }
            FOREGROUND_SERVICE -> {
                doForegroundThings()
                startRecordingSensorData()
            }
            else -> {
                showToast(intentAction ?: "Empty action intent")
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun startRecordingSensorData() {

        Timber.plant(FileLoggingTree(this))

    }

    val samplingFrequencyRange = SensorManager.SENSOR_DELAY_GAME
    val maxReportingFrequency = SensorManager.SENSOR_DELAY_GAME
    private fun registerAsSensorListener() {
        val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelerometer != null) {
            sensorManager?.registerListener(
                this, accelerometer,
                samplingFrequencyRange, maxReportingFrequency
            )
        }

        val magneticField: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (magneticField != null) {
            sensorManager?.registerListener(
                this, magneticField,
                samplingFrequencyRange, maxReportingFrequency
            )
        }
        val gyroscopeSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        if (gyroscopeSensor != null) {
            sensorManager?.registerListener(
                this, gyroscopeSensor,
                samplingFrequencyRange, maxReportingFrequency
            )
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    fun doForegroundThings() {
        showToast("Going foreground")
        createNotificationChannel()
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0)
        isForeGroundService = true
        var builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ice_foreground)
            .setContentTitle("My notification title")
            .setContentText("textContent")
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        val notification = builder.build()
        with(NotificationManagerCompat.from(this)) {
            // notificationId is a unique int for each notification that you must define
            notify(4, notification)
        }

// Notification ID cannot be 0.

        startForeground(4, notification)
    }

    private fun createNotificationChannel() {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = resources.getString(R.string.channel_name)
            val descriptionText = getString(R.string.channel_description)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            // Register the channel with the system
            val notificationManager: NotificationManager =
                getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)

        }
    }

    fun stopService() {
        showToast("Service stopping")
        try {
            stopForeground(true)
            isForeGroundService = false
            stopSelf()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun goBackGround() {
        showToast("Service going in background")
        try {
            stopForeground(true)
            isForeGroundService = false
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        when (accuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH ->
                Logger.log(
                    sensor.toString() + "Accuracy changed : SENSOR_STATUS_ACCURACY_HIGH"
                )
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM ->
                Logger.log(
                    sensor.toString() + "Accuracy changed : SENSOR_STATUS_ACCURACY_MEDIUM"
                )
            SensorManager.SENSOR_STATUS_ACCURACY_LOW ->
                Logger.log(
                    sensor.toString() + "Accuracy changed : SENSOR_STATUS_ACCURACY_LOW"
                )
            SensorManager.SENSOR_STATUS_NO_CONTACT ->
                Logger.log(
                    sensor.toString() + "Accuracy changed : SENSOR_STATUS_NO_CONTACT"
                )
            SensorManager.SENSOR_STATUS_UNRELIABLE ->
                Logger.log(
                    sensor.toString() + "Accuracy changed : SENSOR_STATUS_UNRELIABLE"
                )
        }
        when (sensor?.type) {
            null -> {

            }
            Sensor.TYPE_ACCELEROMETER -> {
                acceleroMeterAccuracy = getAccuracyValue(accuracy).toString()
            }
            Sensor.TYPE_GYROSCOPE -> {
                gyroScopeAccuracy = getAccuracyValue(accuracy).toString()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                magnetoMeterAccuracy = getAccuracyValue(accuracy).toString()
            }
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
        }
        liveDataPositionObject.value = getDataObjectFromCurrentValues()
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

    private fun getAccuracyValue(accuracy: Int): CharSequence? {
        when (accuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> {
                return "High"
            }
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> {
                return "Medium"
            }
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> {
                return "Low"
            }
            SensorManager.SENSOR_STATUS_UNRELIABLE -> {
                return "Unreliable"
            }
            SensorManager.SENSOR_STATUS_NO_CONTACT -> {
                return "No contact"
            }
        }
        return "Unknown"
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
//                TODO find a way to send this exception to activity
                /*try {
                    // Show the dialog by calling startResolutionForResult(),
                    // and check the result in onActivityResult().
                    exception.startResolutionForResult(
                        this@MainActivity,
                        REQUEST_ENABLE_LOCATION
                    )
                } catch (sendEx: IntentSender.SendIntentException) {
                    // Ignore the error.
                    Logger.eLog(MainActivity.TAG, sendEx.toString())
                }*/
            }
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
                    Logger.log("Activity recognition update registration success")
                    transitionEnabled = true
                }

                task.addOnFailureListener { e: Exception ->
                    Logger.log("Activity recognition update registration failed")
                    Logger.log(e.toString())
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

    private fun removeActivityRecognitionUpdates() {
        myPendingIntent?.let { pendingIntent ->
            val task = ActivityRecognition.getClient(this)
                .removeActivityTransitionUpdates(pendingIntent)

            task.addOnSuccessListener {
                pendingIntent.cancel()
                transitionEnabled = false
            }

            task.addOnFailureListener { e: Exception ->
                Logger.log("Activity recognition updates de-registration failed")
                Logger.eLog(MainActivity.TAG, e.message ?: "Unknown error")
            }
        }

    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            sensorManager?.unregisterListener(this)
            removeActivityRecognitionUpdates()
            fusedLocationClient?.removeLocationUpdates(locationCallback)
            unregisterReceiver(activityTransitionBroadcastReceiver)
        } catch (e: Exception) {
            Logger.eLog(TAG, e.toString())
        }
    }

    fun logCurrentData() {
        Logger.log(format.encodeToString(getDataObjectFromCurrentValues()))
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
            gx = gyroScopeReading[0],
            gy = gyroScopeReading[1],
            gz = gyroScopeReading[2],
            lastActivity = lastActivity,
            currentActivity = currentActivity,
            oldHeading = oldHeading
        )
    }
}