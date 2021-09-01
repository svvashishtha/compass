package com.example.compass.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.SensorManager.*
import android.os.*
import android.view.*
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.ShareCompat.IntentBuilder
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import com.example.compass.*
import com.example.compass.R
import com.example.compass.databinding.ActivityMainBinding
import com.example.compass.datacollection.FileLoggingTree
import com.example.compass.datacollection.Logger
import com.example.compass.datacollection.MyService
import com.example.compass.datacollection.objects.PositionDataObject
import com.example.compass.ui.permission.PermissionUtils
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import com.google.android.gms.location.*
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.*


class MainActivity : AppCompatActivity() {
    /**
     * dataRecordingRequested is true when "Start recording" is clicked
     * but Service is not already running. So we start the service and
     * then start recording when service binds with activity*/
    private var dataRecordingRequested: Boolean = false
    private val REQUEST_PERMISSION_FINE_LOCATION: Int = 203
    private val REQUEST_PERMISSION_ACTIVTY_RECOGNITION: Int = 204
    private val REQUEST_ENABLE_LOCATION: Int = 205
    private var binding: ActivityMainBinding? = null
    private var viewModel: SensorDataViewModel? = null

    private val runningQOrLater = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    var mService: MyService? = null
    var mBound = false
    private val connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(
            className: ComponentName,
            service: IBinder
        ) {
            Logger.log(TAG, " in onServiceConnected")
            // We've bound to MyService, cast the IBinder and get MyService instance
            val binder: MyService.LocalBinder = service as MyService.LocalBinder
            mService = binder.getService()
            mBound = true
            if (dataRecordingRequested || mService?.isForeGroundService == true) {
                bringServiceToForeground()

                binding?.toggleService?.text = getString(R.string.stop_service)
            }
            mService?.liveDataPositionObject?.let { viewModel?.setLiveDataObject(it) }
            viewModel?.liveDataPositionObject?.removeObservers(this@MainActivity)
            viewModel?.liveDataPositionObject?.observe(this@MainActivity,
                { positionDataObject ->
                    updateUi(positionDataObject)
                })
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            mBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)

        setContentView(binding?.root!!)
        binding?.context = this
        binding?.lifecycleOwner = this
        requestRequiredPermissions()
        setupViewModel()
        binding?.toggleService?.setOnClickListener {
            if (mBound) {
                if (mService?.isForeGroundService == false) {
                    startRecording()
                } else {
                    stopRecording()
                }
            } else {
                setUpService()
                dataRecordingRequested = true
            }
        }


    }

    private fun setUpChart(xPlot: LineChart?) {
        val dataSetAccelerometer: MutableList<ILineDataSet> = ArrayList()
        val dataAcceleroMeter = LineData(dataSetAccelerometer)
        dataAcceleroMeter.setDrawValues(false)
        xPlot?.visibility = View.VISIBLE
        xPlot?.data = null
        xPlot?.setDrawGridBackground(false)
        xPlot?.setScaleEnabled(false)
        xPlot?.setDrawBorders(false)
        xPlot?.setVisibleXRangeMaximum(10f)
        xPlot?.data = dataAcceleroMeter
        xPlot?.legend?.isEnabled = false
        xPlot?.description?.isEnabled = false
        xPlot?.xAxis?.setDrawGridLines(false);
        xPlot?.axisLeft?.setDrawGridLines(false);
        xPlot?.axisRight?.setDrawGridLines(false);
        xPlot?.axisLeft?.setDrawLabels(false)
        xPlot?.axisRight?.setDrawLabels(false)
        xPlot?.xAxis?.setDrawLabels(false)

    }

    private fun setupViewModel() {
        viewModel = ViewModelProvider(
            this,
            SensorDataViewModelFactory()
        ).get(SensorDataViewModel::class.java)

    }

    /**
     * 1. Tells @MyService to start as foreground service.
     * 2. Sets up ui to listen to @PositionDataObject
     * 3 Updates text on button
     * */
    private fun startRecording() {
        bringServiceToForeground()
        binding?.toggleService?.text = getString(R.string.stop_service)
    }

    /**
     * Sends an intent to stop service.
     * Updates text on button
     * */
    private fun stopRecording() {
        if (mBound) {
            mService?.goBackGround()
        } else {
            val intent = Intent(this@MainActivity, MyService::class.java)
            intent.action = MyService.STOP_SERVICE
            startService(intent)
        }
        binding?.toggleService?.text = getString(R.string.start_recording)
    }

    /**
     * Starts the service and calls bindWithService()
     * */
    private fun setUpService() {
        val intent = Intent(this@MainActivity, MyService::class.java)
        intent.action = MyService.START_SERVICE
        startService(intent)
        bindWithService()
    }


    /**
     * Binds with MyService
     * */
    private fun bindWithService() {
        val intent = Intent(this, MyService::class.java)
        bindService(intent, connection, BIND_IMPORTANT)
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
        }


    }

    fun requestActivityRecognitionPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {//check if we have permission to register for activity recognition events

            if (checkActivityRecognitionPermission()) {
                //fine location permission already granted
//                setUpActivityTransition()
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
//            getLocation()
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
//                getLocation()
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
//                setUpActivityTransition()
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
//                        getLocation()
                    } else {
                        Toast.makeText(this, R.string.location_permission_deneid, Toast.LENGTH_LONG)
                            .show()
                    }
                }
                if (permission == Manifest.permission.ACTIVITY_RECOGNITION) {
                    if (grantResults[index] == PackageManager.PERMISSION_GRANTED) {
//                        setUpActivityTransition()
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
                        Timber.plant(
                            FileLoggingTree(
                                this
                            )
                        )
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

    var pattern = " HH:mm:ss.SSS"
    var simpleDateFormat: SimpleDateFormat = SimpleDateFormat(pattern, Locale.getDefault())
    private fun updateUi(positionDataObject: PositionDataObject) {
        //update accelerometer
        binding?.accelerometerLayout?.x?.text = getString(R.string.ax, positionDataObject.ax)
        binding?.accelerometerLayout?.y?.text = getString(R.string.ay, positionDataObject.ay)
        binding?.accelerometerLayout?.z?.text = getString(R.string.az, positionDataObject.az)
        binding?.gyroscopeLayout?.x?.text = getString(R.string.gx, positionDataObject.gx)
        binding?.gyroscopeLayout?.y?.text = getString(R.string.gy, positionDataObject.gy)
        binding?.gyroscopeLayout?.z?.text = getString(R.string.gz, positionDataObject.gz)
        binding?.magnetometerLayout?.x?.text = getString(R.string.mx, positionDataObject.mx)
        binding?.magnetometerLayout?.y?.text = getString(R.string.my, positionDataObject.my)
        binding?.magnetometerLayout?.z?.text = getString(R.string.mz, positionDataObject.mz)
        updateHeading(positionDataObject)
        binding?.activityExit?.text = String.format(
            "%1s : %2s",
            positionDataObject.lastActivity,
            "Exit"
        )
        binding?.activityEntry?.text = String.format(
            "%1s : %2s",
            positionDataObject.currentActivity,
            "Enter"
        )
        binding?.altitude?.text = getString(R.string.altitude, positionDataObject.altitude)
        binding?.latitude?.text = getString(R.string.latitude, positionDataObject.latitude)
        binding?.longitude?.text = getString(R.string.longitude, positionDataObject.longitude)
        binding?.speed?.text = getString(R.string.speed, positionDataObject.speed)
        binding?.accuracy?.text = getString(R.string.accuracy, positionDataObject.accuracy)
        binding?.time?.text =
            getString(R.string.time, simpleDateFormat.format(Date(positionDataObject.time)))
        binding?.textViewMagneticDeclination?.text =
            getString(R.string.magnetic_declination, positionDataObject.declination)
        binding?.textViewTrueHeading?.text =
            getString(R.string.true_heading, positionDataObject.trueHeading)
        binding?.textViewHeading?.text = getString(R.string.heading, positionDataObject.heading)
        binding?.accelerometerAccuracy?.text =
            getString(R.string.accelerometer_accuracy, positionDataObject?.acceleroMeterAccuracy)
        binding?.magnetometerAccuracy?.text =
            getString(R.string.magnetometer_accuracy, positionDataObject?.magnetoMeterAccuracy)
        binding?.gyroscopeAccuracy?.text =
            getString(R.string.gyroscope_accuracy, positionDataObject?.gyroScopeAccuracy)

        setChartData(binding?.accelerometerLayout?.xPlot, positionDataObject.ax)
        setChartData(binding?.accelerometerLayout?.yPlot, positionDataObject.ay)
        setChartData(binding?.accelerometerLayout?.zPlot, positionDataObject.az)
        setChartData(binding?.magnetometerLayout?.xPlot, positionDataObject.mx)
        setChartData(binding?.magnetometerLayout?.yPlot, positionDataObject.my)
        setChartData(binding?.magnetometerLayout?.zPlot, positionDataObject.mz)
        setChartData(binding?.gyroscopeLayout?.xPlot, positionDataObject.gx)
        setChartData(binding?.gyroscopeLayout?.yPlot, positionDataObject.gy)
        setChartData(binding?.gyroscopeLayout?.zPlot, positionDataObject.gz)

    }

    var count = 0
    private fun setChartData(plot: LineChart?, ax: Float) {
        val data = plot?.data
        data?.let { accelerometerData ->
            var set = accelerometerData.getDataSetByIndex(0)
            if (set == null) {
                set = createSet()
                accelerometerData.addDataSet(set)
            }
            if (set.entryCount > 200) {
                set.removeEntry(0)
            }
            val entry =
                Entry(count.toFloat(), ax + 5)
            accelerometerData.addEntry(
                entry,
                0
            )
            count++

            accelerometerData.notifyDataChanged()
            plot.apply {
                notifyDataSetChanged()
                moveViewToX(accelerometerData.entryCount.toFloat())
            }
        }
    }

    private fun createSet(): LineDataSet? {
        val set = LineDataSet(null, "")
        set.axisDependency = YAxis.AxisDependency.LEFT
        set.lineWidth = 1f
        val rnd = Random()
        val color = Color.argb(255, rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
        set.color = color
        set.isHighlightEnabled = false
        set.setDrawValues(false)
        set.setDrawCircles(false)
        set.mode = LineDataSet.Mode.CUBIC_BEZIER
        set.cubicIntensity = 0.2f
        return set
    }


    private fun updateHeading(positionDataObject: PositionDataObject) {
        //oldHeading required for image rotate animation
        val rotateAnimation = RotateAnimation(
            -positionDataObject.oldHeading.toFloat(),
            -positionDataObject.heading.toFloat(),
            Animation.RELATIVE_TO_SELF,
            0.5f,
            Animation.RELATIVE_TO_SELF,
            0.5f
        )
        rotateAnimation.duration = 500
        rotateAnimation.fillAfter = true
        binding?.imageCompass?.startAnimation(rotateAnimation)
    }

    override fun onResume() {
        super.onResume()
        Logger.log(TAG, " in Onresume")
        if (!mBound) {
            Logger.log(TAG, " in Onresume setting up service")
            setUpService()
        }
//        setUpAllCharts()

    }

    private fun setUpAllCharts() {
        count = 0
        setUpChart(binding?.accelerometerLayout?.xPlot)
        setUpChart(binding?.accelerometerLayout?.yPlot)
        setUpChart(binding?.accelerometerLayout?.zPlot)
        setUpChart(binding?.magnetometerLayout?.xPlot)
        setUpChart(binding?.magnetometerLayout?.yPlot)
        setUpChart(binding?.magnetometerLayout?.zPlot)
        setUpChart(binding?.gyroscopeLayout?.xPlot)
        setUpChart(binding?.gyroscopeLayout?.yPlot)
        setUpChart(binding?.gyroscopeLayout?.zPlot)
    }

    override fun onPause() {
        super.onPause()
        Logger.log(TAG, " in onPause ")
        if (mBound) {
            Logger.log(TAG, " in onPause unbinding service")
            unbindService(connection)
            mBound = false
        }
        clearCharts(binding?.accelerometerLayout?.xPlot)
        clearCharts(binding?.accelerometerLayout?.yPlot)
        clearCharts(binding?.accelerometerLayout?.zPlot)
        clearCharts(binding?.magnetometerLayout?.xPlot)
        clearCharts(binding?.magnetometerLayout?.yPlot)
        clearCharts(binding?.magnetometerLayout?.zPlot)
        clearCharts(binding?.gyroscopeLayout?.xPlot)
        clearCharts(binding?.gyroscopeLayout?.yPlot)
        clearCharts(binding?.gyroscopeLayout?.zPlot)
    }

    override fun onStop() {
        super.onStop()
    }

    private fun clearCharts(xPlot: LineChart?) {
        xPlot?.data = null
    }


    companion object {
        const val TAG = "Sensor_MainActivity"
    }


    private fun bringServiceToForeground() {
        mService?.let {
            if (!it.isForeGroundService) {
                val intent = Intent(this, MyService::class.java)
                intent.action = MyService.FOREGROUND_SERVICE
                ContextCompat.startForegroundService(this, intent)
                mService!!.doForegroundThings()
            } else {
                Logger.log(TAG, "Service is already in foreground")
            }
        } ?: Logger.log(TAG, "Service is null")

    }


    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        val inflater: MenuInflater = menuInflater
        inflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.share_logs -> {
                shareLogs()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }


    }

    private fun shareLogs() {
        val logDirectoryPath: String = getExternalFilesDir(null).toString() + "/logs"
        val logsDirectory = File(logDirectoryPath)
        if (logsDirectory.isDirectory) {
            val logFiles = logsDirectory.listFiles()
            if (logFiles != null && logFiles.isNotEmpty()) {
                startFileShareIntent(logFiles)
            }
        }
    }

    fun startFileShareIntent(files: Array<File>?) {
        val shareIntent = IntentBuilder(this)
        shareIntent.setType("*/*")
        if (files != null) {
            for (logFile in files) {
                val fileURI = FileProvider.getUriForFile(
                    this@MainActivity, "$packageName.fileprovider",
                    logFile
                )
                shareIntent.addStream(fileURI)
            }
            val intent = shareIntent.intent
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "Choose..."))
        } else {
            Toast.makeText(this, "No log files found", Toast.LENGTH_LONG).show()
        }
    }


}