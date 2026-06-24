package app.aaps.plugins.source

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.utils.receivers.DataWorkerStorage
import androidx.work.OneTimeWorkRequest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 动态注册的广播接收器，用于接收微泰 Aidex2 的 CGM 数据。
 *
 * Android 8.0+ 限制了后台应用接收隐式广播，因此需要动态注册
 * 而不是在 AndroidManifest 中静态注册。
 */
@Singleton
class Aidex2BroadcastReceiver @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val dataWorkerStorage: DataWorkerStorage
) : BroadcastReceiver() {

    private var isRegistered = false

    override fun onReceive(context: Context, intent: Intent) {
        aapsLogger.debug(LTag.BGSOURCE, "Aidex2BroadcastReceiver: 收到广播 action=${intent.action}")

        val bundle = intent.extras
        if (bundle == null) {
            aapsLogger.debug(LTag.BGSOURCE, "Aidex2BroadcastReceiver: Bundle 为空")
            return
        }

        aapsLogger.debug(LTag.BGSOURCE, "Aidex2BroadcastReceiver: Bundle keys=${bundle.keySet()}")
        for (key in bundle.keySet()) {
            val value = bundle.get(key)
            aapsLogger.debug(LTag.BGSOURCE, "Aidex2BroadcastReceiver: Bundle[$key] = ${value?.toString()?.take(200)} (type: ${value?.javaClass?.simpleName})")
        }

        // 将数据转发给 Aidex2Worker
        val request = OneTimeWorkRequest.Builder(Aidex2Plugin.Aidex2Worker::class.java)
            .setInputData(dataWorkerStorage.storeInputData(bundle, intent.action))
            .build()
        dataWorkerStorage.enqueue(request)

        aapsLogger.debug(LTag.BGSOURCE, "Aidex2BroadcastReceiver: 已创建 WorkManager 任务")
    }

    /**
     * 注册广播接收器
     */
    fun register(context: Context) {
        if (isRegistered) {
            aapsLogger.debug(LTag.BGSOURCE, "Aidex2BroadcastReceiver: 已注册，跳过")
            return
        }

        val intentFilter = IntentFilter().apply {
            addAction(ACTION_CGM_DATA)
            addAction(ACTION_CAL_DATA)
            addAction(ACTION_NEW_SENSOR)
            addAction(ACTION_REMAIN_TIME)
        }

        context.registerReceiver(this, intentFilter)
        isRegistered = true
        aapsLogger.debug(LTag.BGSOURCE, "Aidex2BroadcastReceiver: 注册成功")
    }

    /**
     * 注销广播接收器
     */
    fun unregister(context: Context) {
        if (!isRegistered) {
            aapsLogger.debug(LTag.BGSOURCE, "Aidex2BroadcastReceiver: 未注册，跳过")
            return
        }

        try {
            context.unregisterReceiver(this)
            isRegistered = false
            aapsLogger.debug(LTag.BGSOURCE, "Aidex2BroadcastReceiver: 注销成功")
        } catch (e: IllegalArgumentException) {
            aapsLogger.debug(LTag.BGSOURCE, "Aidex2BroadcastReceiver: 注销时出错 - ${e.message}")
        }
    }

    companion object {
        const val ACTION_CGM_DATA = "com.microtech.aidexx.broadcast.action.CGM_DATA"
        const val ACTION_CAL_DATA = "com.microtech.aidexx.broadcast.action.CAL_DATA"
        const val ACTION_NEW_SENSOR = "com.microtech.aidexx.broadcast.action.NEW_SENSOR"
        const val ACTION_REMAIN_TIME = "com.microtech.aidexx.broadcast.action.REMAIN_TIME"
    }
}
