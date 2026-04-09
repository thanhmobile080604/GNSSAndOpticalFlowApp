package com.example.gnssandopticalflowapp

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class MainViewModel :
    ViewModel() {
    val currentTab = MutableLiveData<Int>(0)
    val selectedVideoPath = MutableLiveData<String>()
}