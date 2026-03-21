package com.example.gnssandopticalflowapp.base

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

abstract class BaseActivity : AppCompatActivity() {
//    private val backHandler by lazy { Handler(Looper.getMainLooper()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
//        initNetwork()
    }

    override fun onDestroy() {
//        backHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }


}