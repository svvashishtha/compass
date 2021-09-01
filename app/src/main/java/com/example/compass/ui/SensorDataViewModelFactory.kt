package com.example.compass.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class SensorDataViewModelFactory : ViewModelProvider.Factory {
    override fun <T : ViewModel?> create(modelClass: Class<T>): T {
        when (modelClass) {
            SensorDataViewModel::class.java -> {
                return SensorDataViewModel() as T
            }
        }
        throw(Exception("Unexpected view model requested"))
    }

}
