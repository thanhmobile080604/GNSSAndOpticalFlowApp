package com.example.gnssandopticalflowapp


import android.location.Location
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow

class MainViewModel :
    ViewModel() {
    val currentTab = MutableLiveData<Int>(0)
    val selectedVideoPath = MutableLiveData<String>()
    
    val currentLocation = MutableLiveData<Location?>()
    val currentTime = MutableLiveData<String>()

    // Suppression flag for global "No GPS/Location" dialogs
    val isResolvingDeviceSettings = MutableStateFlow(false)
}