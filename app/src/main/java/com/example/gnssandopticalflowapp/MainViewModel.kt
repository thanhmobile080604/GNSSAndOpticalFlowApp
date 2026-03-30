package com.example.gnssandopticalflowapp

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class MainViewModel :
    ViewModel() {

    val isNetworkAvailable = MutableLiveData<Boolean>()
}