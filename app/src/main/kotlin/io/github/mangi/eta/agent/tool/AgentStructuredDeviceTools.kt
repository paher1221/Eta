package io.github.mangi.eta.agent.tool

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.Settings
import android.view.KeyEvent
import io.github.mangi.eta.agent.device.AgentNotificationHistoryService
import io.github.mangi.eta.agent.device.DeviceToolContract
import io.github.mangi.eta.agent.device.FlashlightController
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.core.AgentLogger
import java.util.Calendar
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** 常用设备能力的执行路由；全部通过公开系统接口完成，不依赖 Root。 */
internal class AgentStructuredDeviceTools(
    private val context: Context,
    private val logger: AgentLogger,
    private val foregroundAction: (String, () -> JSONObject) -> JSONObject = { _, action -> action() },
) {
    private val personalContextTools = AgentPersonalContextTools(context)
    private val appInspection = AppInspectionTool(context)

    fun execute(name: String, args: JSONObject): AgentModelClient.ToolResult? = when (name) {
        "get_flashlight" ->
            sensitive(FlashlightController.get(context).state().toString())
        "set_flashlight" ->
            sensitive(
                FlashlightController.get(context)
                    .set(booleanArg(args, "enabled"), DeviceToolContract.appUserId(context))
                    .toString()
            )
        "get_display_state" -> sensitive(displaySound().toString())
        "get_sound_state" -> sensitive(soundState().toString())
        "set_do_not_disturb" -> sensitive(setDnd(args).toString())
        "set_volume" -> text(setVolume(args))
        "set_alarm" -> text(setAlarm(args))
        "set_timer" -> text(setTimer(args))
        "inspect_app" -> text(appInspection.inspect(args).toString())
        "device_status" -> text(deviceStatus())
        "network_info" -> text(networkInfo())
        "media_control" -> text(mediaControl(args))
        else -> personalContextTools.execute(name, args)
    }

    private fun booleanArg(args: JSONObject, name: String): Boolean =
        args.opt(name) as? Boolean ?: throw IllegalArgumentException("$name 必须是布尔值")

    private fun displaySound(): JSONObject {
        val brightness = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrNull()
        val brightnessPercent = brightness?.takeIf { it in 0..255 }
            ?.let { Math.round(it * 100.0 / 255.0) }
        return JSONObject()
            .put("ok", true)
            .put("tool", "get_display_state")
            .put("brightness_percent", brightnessPercent ?: JSONObject.NULL)
            .put(
                "auto_brightness",
                runCatching {
                    Settings.System.getInt(
                        context.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        0,
                    )
                }.getOrDefault(0) == 1,
            )
            .put(
                "auto_rotate",
                runCatching {
                    Settings.System.getInt(
                        context.contentResolver,
                        Settings.System.ACCELEROMETER_ROTATION,
                        0,
                    )
                }.getOrDefault(0) == 1,
            )
            .put(
                "screen_timeout_seconds",
                runCatching {
                    Settings.System.getLong(
                        context.contentResolver,
                        Settings.System.SCREEN_OFF_TIMEOUT,
                        0,
                    )
                }.getOrDefault(0) / 1000,
            )
    }

    private fun soundState(): JSONObject {
        val audio = context.getSystemService(AudioManager::class.java)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        return JSONObject()
            .put("ok", audio != null)
            .put("tool", "get_sound_state")
            .put(
                "ringer_mode",
                when (audio?.ringerMode) {
                    0 -> "silent"
                    1 -> "vibrate"
                    2 -> "normal"
                    else -> JSONObject.NULL
                },
            )
            .put(
                "do_not_disturb",
                notificationManager?.currentInterruptionFilter ?: JSONObject.NULL,
            )
            .put(
                "streams",
                JSONObject().also { streams ->
                    mapOf(
                            "media" to 3,
                            "ring" to 2,
                            "notification" to 5,
                            "alarm" to 4,
                            "voice_call" to 0,
                            "system" to 1,
                        )
                        .forEach { (name, stream) ->
                            if (audio != null)
                                streams.put(
                                    name,
                                    JSONObject()
                                        .put("level", audio.getStreamVolume(stream))
                                        .put("max_level", audio.getStreamMaxVolume(stream)),
                                )
                        }
                },
            )
    }

    private fun setDnd(args: JSONObject): JSONObject {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
            ?: throw IllegalStateException("系统通知服务不可用")
        val mode = args.optString("mode")
        val filter = when (mode) {
            "off" -> NotificationManager.INTERRUPTION_FILTER_ALL
            "priority" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
            "silent" -> NotificationManager.INTERRUPTION_FILTER_NONE
            "alarms" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
            else -> throw IllegalArgumentException("勿扰模式无效")
        }
        val before = notificationManager.currentInterruptionFilter
        val after = if (before == filter) before else notificationManager.setInterruptionFilter(filter)
        return DeviceMutationResult.observed(
            "set_do_not_disturb",
            DeviceToolContract.appUserId(context),
            "device",
            before,
            after,
            filter,
            after == filter,
        )
    }

    private fun setVolume(args: JSONObject): String {
        val userId = DeviceToolContract.appUserId(context)
        DeviceToolContract.userScopeError(args, userId)?.let { return it.toString() }
        val streamName = args.optString("stream").lowercase(Locale.ROOT)
        val stream = when (streamName) {
            "media" -> AudioManager.STREAM_MUSIC
            "alarm" -> AudioManager.STREAM_ALARM
            "ring" -> AudioManager.STREAM_RING
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "voice_call" -> AudioManager.STREAM_VOICE_CALL
            "system" -> AudioManager.STREAM_SYSTEM
            else -> return error("INVALID_ARGUMENT", "不支持的音量通道")
        }
        val percent = args.optInt("percent", -1)
        if (percent !in 0..100) return error("INVALID_ARGUMENT", "percent 必须在 0 到 100 之间")
        val audio = context.getSystemService(AudioManager::class.java)
            ?: return error("AUDIO_SERVICE_UNAVAILABLE", "系统音频服务不可用")
        return try {
            val min = audio.getStreamMinVolume(stream)
            val max = audio.getStreamMaxVolume(stream).coerceAtLeast(min)
            val requested = (min + (max - min) * percent / 100.0).toInt().coerceIn(min, max)
            val before = audio.getStreamVolume(stream)
            audio.setStreamVolume(stream, requested, 0)
            val after = audio.getStreamVolume(stream)
            DeviceMutationResult.observed("set_volume", userId, "device", before, after, requested, true)
                .put("stream", streamName).put("percent", percent).put("level", after)
                .put("min_level", min).put("max_level", max).toString()
        } catch (_: SecurityException) {
            error("VOLUME_CHANGE_DENIED", "系统拒绝修改该音量通道")
        } catch (_: RuntimeException) {
            DeviceToolContract.failure("VOLUME_CHANGE_FAILED", "音量修改或状态读回失败，结果尚未确认")
                .put("verified", false).toString()
        }
    }

    private fun setAlarm(args: JSONObject): String {
        val hour = args.getInt("hour")
        val minute = args.getInt("minute")
        val intent =
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .putExtra(AlarmClock.EXTRA_VIBRATE, args.optBoolean("vibrate", true))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        args.optString("label").trim().takeIf(String::isNotBlank)?.let {
            intent.putExtra(AlarmClock.EXTRA_MESSAGE, it)
        }
        val repeatDays = args.optJSONArray("repeat_days")
        if (repeatDays != null && repeatDays.length() > 0) {
            intent.putIntegerArrayListExtra(
                AlarmClock.EXTRA_DAYS,
                ArrayList(
                    (0 until repeatDays.length()).map { index ->
                        repeatDays.getString(index).toCalendarDay()
                    }
                ),
            )
        }
        return startClockIntent(
                directIntent = intent,
                fallbackAction = AlarmClock.ACTION_SHOW_ALARMS,
                tool = "set_alarm",
            )
            .put("hour", hour)
            .put("minute", minute)
            .toString()
    }

    private fun setTimer(args: JSONObject): String {
        val seconds = args.getInt("duration_seconds")
        val intent =
            Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        args.optString("label").trim().takeIf(String::isNotBlank)?.let {
            intent.putExtra(AlarmClock.EXTRA_MESSAGE, it)
        }
        return startClockIntent(
                directIntent = intent,
                fallbackAction = AlarmClock.ACTION_SHOW_TIMERS,
                tool = "set_timer",
            )
            .put("duration_seconds", seconds)
            .toString()
    }

    private fun startClockIntent(
        directIntent: Intent,
        fallbackAction: String,
        tool: String,
    ): JSONObject = foregroundAction(tool) {
        dispatchClockIntent(directIntent, fallbackAction, tool)
    }

    private fun dispatchClockIntent(
        directIntent: Intent,
        fallbackAction: String,
        tool: String,
    ): JSONObject {
        val packageManager = context.packageManager
        val preferred = Intent(directIntent).setPackage(COLOROS_CLOCK_PACKAGE)
        val direct =
            when {
                preferred.resolveActivity(packageManager) != null -> preferred
                directIntent.resolveActivity(packageManager) != null -> directIntent
                else -> null
            }
        if (direct != null && runCatching { context.startActivity(direct) }.isSuccess) {
            logger.info("Agent direct tool action=$tool outcome=dispatched")
            return JSONObject()
                .put("ok", true)
                .put("tool", tool)
                .put("mode", "direct")
                .put("status", "dispatched")
                .put("verified", false)
        }

        val fallback =
            Intent(fallbackAction)
                .setPackage(COLOROS_CLOCK_PACKAGE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .takeIf { it.resolveActivity(packageManager) != null }
                ?: packageManager
                    .getLaunchIntentForPackage(COLOROS_CLOCK_PACKAGE)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (fallback != null && runCatching { context.startActivity(fallback) }.isSuccess) {
            return JSONObject()
                .put("ok", false)
                .put("code", "DIRECT_CLOCK_ACTION_FAILED")
                .put("message", "系统未确认直接创建，已打开时钟页面，请让用户完成确认")
                .put("tool", tool)
                .put("mode", "ui_fallback")
        }
        return JSONObject(error("CLOCK_UNAVAILABLE", "没有可处理该请求的时钟应用"))
    }

    private fun deviceStatus(): String {
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also(activity::getMemoryInfo)
        val storage = StatFs(Environment.getDataDirectory().absolutePath)
        return JSONObject()
            .put("ok", true)
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("android_version", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("security_patch", Build.VERSION.SECURITY_PATCH)
            .put("uptime_ms", SystemClock.elapsedRealtime())
            .put(
                "battery_percent",
                battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            )
            .put("charging", battery.isCharging)
            .put("memory_available_bytes", memory.availMem)
            .put("memory_total_bytes", memory.totalMem)
            .put("storage_available_bytes", storage.availableBytes)
            .put("storage_total_bytes", storage.totalBytes)
            .toString()
    }

    private fun networkInfo(): String {
        val connectivity =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.activeNetwork
        val capabilities = network?.let(connectivity::getNetworkCapabilities)
        val transports =
            JSONArray().also { array ->
                if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
                    array.put("wifi")
                if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true)
                    array.put("cellular")
                if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true)
                    array.put("ethernet")
                if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
                    array.put("vpn")
            }
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = runCatching { wifi.connectionInfo }.getOrNull()
        return JSONObject()
            .put("ok", true)
            .put("connected", capabilities != null)
            .put(
                "validated",
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            )
            .put(
                "metered",
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true,
            )
            .put("transports", transports)
            .put("wifi_enabled", wifi.isWifiEnabled)
            .also { result ->
                val ssid = info?.ssid?.takeUnless { it == WifiManager.UNKNOWN_SSID }?.trim('"')
                val rssi = info?.rssi?.takeUnless { it == -127 }
                ssid?.let { result.put("ssid", it) }
                rssi?.let { result.put("rssi_dbm", it) }
            }
            .toString()
    }

    private fun mediaControl(args: JSONObject): String {
        val keyCode =
            when (args.getString("action").lowercase(Locale.ROOT)) {
                "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
                "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
                else -> return error("INVALID_ARGUMENT", "不支持的媒体动作")
            }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        return ok("media_control").put("action", args.getString("action")).toString()
    }

    private fun String.toCalendarDay(): Int =
        when (lowercase(Locale.ROOT)) {
            "sun" -> Calendar.SUNDAY
            "mon" -> Calendar.MONDAY
            "tue" -> Calendar.TUESDAY
            "wed" -> Calendar.WEDNESDAY
            "thu" -> Calendar.THURSDAY
            "fri" -> Calendar.FRIDAY
            "sat" -> Calendar.SATURDAY
            else -> throw IllegalArgumentException("不支持的重复日期")
        }

    private fun ok(tool: String): JSONObject = JSONObject().put("ok", true).put("tool", tool)

    private fun error(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("message", message).toString()

    private fun text(content: String) = AgentModelClient.ToolResult(content)

    private fun sensitive(content: String) =
        AgentModelClient.ToolResult(content = content, sensitive = true)

    private companion object {
        const val COLOROS_CLOCK_PACKAGE = "com.coloros.alarmclock"
    }
}
