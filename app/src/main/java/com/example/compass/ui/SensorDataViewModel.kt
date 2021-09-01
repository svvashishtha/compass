package com.example.compass.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.compass.datacollection.objects.PositionDataObject

class SensorDataViewModel : ViewModel() {
    var liveDataPositionObject: LiveData<PositionDataObject>? = null


    fun setLiveDataObject(ldObject: MutableLiveData<PositionDataObject>) {
        liveDataPositionObject = ldObject
    }
}