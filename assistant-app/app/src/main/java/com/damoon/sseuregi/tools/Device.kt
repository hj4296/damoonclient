package com.damoon.sseuregi.tools

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import com.damoon.sseuregi.brain.Command.Setting

/** 손전등, 소리, 타이머, 알람, 설정 화면 같은 휴대폰 기능 */
object Device {

    fun torch(context: Context, on: Boolean): Boolean = try {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        if (id == null) false else {
            cm.setTorchMode(id, on)
            true
        }
    } catch (e: Exception) {
        false
    }

    fun volume(context: Context, change: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val dir = when {
            change > 0 -> AudioManager.ADJUST_RAISE
            change < 0 -> AudioManager.ADJUST_LOWER
            else -> AudioManager.ADJUST_MUTE
        }
        repeat(if (change == 0) 1 else 2) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
        }
    }

    fun battery(context: Context): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    fun isCharging(context: Context): Boolean {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.isCharging
    }

    fun timer(context: Context, seconds: Int): Boolean = start(
        context,
        Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "쓰레기 타이머")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false),
    )

    fun alarm(context: Context, hour: Int, minute: Int): Boolean = start(
        context,
        Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "쓰레기 알람")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false),
    )

    fun showAlarms(context: Context): Boolean = start(context, Intent(AlarmClock.ACTION_SHOW_ALARMS))

    fun camera(context: Context): Boolean = start(context, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))

    fun openUrl(context: Context, url: String): Boolean = start(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    fun settings(context: Context, s: Setting): Boolean {
        val q = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val action = when (s) {
            Setting.WIFI -> if (q) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
            Setting.BLUETOOTH -> Settings.ACTION_BLUETOOTH_SETTINGS
            Setting.AIRPLANE -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            Setting.BRIGHTNESS -> Settings.ACTION_DISPLAY_SETTINGS
            Setting.DATA -> if (q) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_DATA_ROAMING_SETTINGS
            Setting.HOTSPOT -> Settings.ACTION_WIRELESS_SETTINGS
            Setting.LOCATION -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            Setting.SOUND -> if (q) Settings.Panel.ACTION_VOLUME else Settings.ACTION_SOUND_SETTINGS
        }
        return start(context, Intent(action)) || start(context, Intent(Settings.ACTION_SETTINGS))
    }

    fun settingName(s: Setting): String = when (s) {
        Setting.WIFI -> "와이파이"
        Setting.BLUETOOTH -> "블루투스"
        Setting.AIRPLANE -> "비행기 모드"
        Setting.BRIGHTNESS -> "화면 밝기"
        Setting.DATA -> "모바일 데이터"
        Setting.HOTSPOT -> "핫스팟"
        Setting.LOCATION -> "위치"
        Setting.SOUND -> "소리"
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }
}
