package com.damoon.sseuregi.brain

/** 말 한 마디를 알아들은 결과. 안드로이드 코드에 기대지 않아서 컴퓨터에서 바로 시험할 수 있다. */
sealed class Command {
    enum class Engine { GOOGLE, NAVER, YOUTUBE }
    enum class Folder { DOWNLOAD, CAMERA, SCREENSHOTS, DOCUMENTS, MUSIC, MOVIES }
    enum class Setting { WIFI, BLUETOOTH, AIRPLANE, BRIGHTNESS, DATA, HOTSPOT, LOCATION, SOUND }

    data class WebSearch(val query: String, val engine: Engine = Engine.GOOGLE) : Command()
    data class OpenApp(val name: String) : Command()
    data class OpenSettings(val setting: Setting) : Command()
    data class Tap(val target: String) : Command()
    data class TapNth(val index: Int) : Command()
    data class TypeText(val text: String) : Command()
    data class Scroll(val down: Boolean) : Command()
    data class Volume(val change: Int) : Command()          // +1 크게, -1 작게, 0 음소거
    data class Flashlight(val on: Boolean) : Command()
    data class Timer(val seconds: Int?) : Command()
    data class Alarm(val hour: Int?, val minute: Int) : Command()
    data class FindFile(val name: String) : Command()
    data class OpenFile(val name: String) : Command()
    data class ReadFile(val name: String) : Command()
    data class ListFolder(val folder: Folder) : Command()

    object Enter : Command()
    object Back : Command()
    object Home : Command()
    object Recents : Command()
    object Notifications : Command()
    object Screenshot : Command()
    object ReadScreen : Command()
    object Camera : Command()
    object Stop : Command()
    object Time : Command()
    object Date : Command()
    object Battery : Command()
    object Hello : Command()
    object Name : Command()
    object Help : Command()
    object Nothing : Command()

    override fun toString(): String = javaClass.simpleName
}
