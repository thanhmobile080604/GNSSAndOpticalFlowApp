package com.example.gnssandopticalflowapp


import android.location.Location
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class MainViewModel :
    ViewModel() {
    val currentTab = MutableLiveData<Int>(0)
    val selectedVideoPath = MutableLiveData<String>()
    
    val currentLocation = MutableLiveData<Location?>()
    val currentTime = MutableLiveData<String>()
}