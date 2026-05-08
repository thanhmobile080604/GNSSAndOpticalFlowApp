package com.example.gnssandopticalflowapp


import android.location.Location
import android.os.SystemClock
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.gnssandopticalflowapp.common.Constants
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

class MainViewModel :
    ViewModel() {
    val currentTab = MutableLiveData<Int>(0)
    val isGnss3DMode = MutableLiveData(false)
    val selectedVideoPath = MutableLiveData<String>()
    
    private val _currentLocation = MutableLiveData<Location?>()
    val currentLocation: LiveData<Location?> = _currentLocation
    private val _currentTime = MutableLiveData<String>()
    val currentTime: LiveData<String> = _currentTime

    // Suppression flag for global "No GPS/Location" dialogs
    val isResolvingDeviceSettings = MutableStateFlow(false)

    fun setCurrentLocation(location: Location?) {
        _currentLocation.value = getEffectiveLocation(location)
    }

    fun postCurrentLocation(location: Location?) {
        _currentLocation.postValue(getEffectiveLocation(location))
    }

    fun seedFakeLocationIfNeeded() {
        if (Constants.USE_FAKE_LOCATION) {
            setCurrentLocation(null)
            setCurrentTime()
        }
    }

    fun setCurrentTime(timeMillis: Long = System.currentTimeMillis()) {
        _currentTime.value = formatDisplayTime(timeMillis)
    }

    fun postCurrentTime(timeMillis: Long = System.currentTimeMillis()) {
        _currentTime.postValue(formatDisplayTime(timeMillis))
    }

    fun formatDisplayTime(timeMillis: Long): String {
        val timeZone = if (Constants.USE_FAKE_LOCATION) {
            TimeZone.getTimeZone(Constants.FAKE_LOCATION_TIME_ZONE_ID)
        } else {
            TimeZone.getDefault()
        }
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply {
            this.timeZone = timeZone
        }
        val offsetMillis = timeZone.getOffset(timeMillis)
        val sign = if (offsetMillis >= 0) "+" else "-"
        val offHours = abs(offsetMillis) / 3600000
        val offMinutes = (abs(offsetMillis) % 3600000) / 60000
        val utcSuffix = "UTC$sign" + String.format(Locale.US, "%02d:%02d", offHours, offMinutes)

        return "${sdf.format(Date(timeMillis))} $utcSuffix"
    }

    fun getEffectiveLocation(location: Location?): Location? {
        if (!Constants.USE_FAKE_LOCATION) return location

        return Location(Constants.FAKE_LOCATION_PROVIDER).apply {
            latitude = Constants.FAKE_LOCATION_LATITUDE
            longitude = Constants.FAKE_LOCATION_LONGITUDE
            altitude = Constants.FAKE_LOCATION_ALTITUDE
            accuracy = Constants.FAKE_LOCATION_ACCURACY_METERS
            speed = Constants.FAKE_LOCATION_SPEED_METERS_PER_SECOND
            bearing = Constants.FAKE_LOCATION_BEARING_DEGREES
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
    }
}
