package app.aaps.plugins.source

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.source.BgSource
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.workflow.LoggingWorker
import app.aaps.core.utils.receivers.DataWorkerStorage
import app.aaps.plugins.source.keys.Aidex2LongKey
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Aidex2Plugin @Inject constructor(
    rh: ResourceHelper,
    aapsLogger: AAPSLogger,
    preferences: Preferences
) : AbstractBgSourcePlugin(
    PluginDescription()
        .mainType(PluginType.BGSOURCE)
        .fragmentClass(BGSourceFragment::class.java.name)
        .pluginIcon(app.aaps.core.objects.R.drawable.ic_generic_cgm)
        .preferencesId(PluginDescription.PREFERENCE_SCREEN)
        .pluginName(R.string.aidex2)
        .preferencesVisibleInSimpleMode(false)
        .description(R.string.description_source_aidex2),
    ownPreferences = listOf(Aidex2LongKey::class.java),
    aapsLogger, rh, preferences
), BgSource {

    class Aidex2Worker(
        context: Context,
        params: WorkerParameters
    ) : LoggingWorker(context, params, Dispatchers.IO) {

        @Inject lateinit var aidex2Plugin: Aidex2Plugin
        @Inject lateinit var dataWorkerStorage: DataWorkerStorage
        @Inject lateinit var dateUtil: DateUtil
        @Inject lateinit var persistenceLayer: PersistenceLayer
        @Inject lateinit var preferences: Preferences

        @SuppressLint("CheckResult")
        override suspend fun doWorkAndLog(): Result {
            var ret = Result.success()
            if (!aidex2Plugin.isEnabled()) return Result.success(workDataOf("Result" to "Plugin not enabled"))
            val bundle = dataWorkerStorage.pickupBundle(inputData.getLong(DataWorkerStorage.STORE_KEY, -1))
                ?: return Result.failure(workDataOf("Error" to "missing input data"))

            return try {
                val glucoseValues = parseBundle(bundle)
                    .sortedBy { it.timestamp }
                    .distinctBy { it.timestamp }
                if (glucoseValues.isNotEmpty()) {
                    persistenceLayer.insertCgmSourceData(Sources.Aidex, glucoseValues, emptyList(), null)
                        .doOnError { ret = Result.failure(workDataOf("Error" to it.toString())) }
                        .blockingGet()
                    preferences.put(Aidex2LongKey.LastProcessedTimestamp, glucoseValues.maxOf { it.timestamp })
                }
                ret
            } catch (e: Exception) {
                aapsLogger.error("Error while processing Aidex2 data", e)
                Result.failure(workDataOf("Error" to e.toString()))
            }
        }

        private fun parseBundle(bundle: Bundle): List<GV> {
            val payload = bundle.getString(AIDEX2_DATA_KEY)
                ?: bundle.getString("data")
                ?: bundle.getBundle(AIDEX2_DATA_KEY)
                ?: bundle.getBundle("data")
                ?: bundle.get(AIDEX2_DATA_KEY)
                ?: bundle.get("data")
                ?: bundle
            return when (payload) {
                is String -> parseJsonPayload(payload)
                is Bundle -> listOfNotNull(parseRecord(Aidex2Record.fromBundle(payload)))
                else      -> listOfNotNull(parseRecord(Aidex2Record.fromObject(payload)))
            }
        }

        private fun parseJsonPayload(payload: String): List<GV> {
            if (payload.isBlank()) return emptyList()
            return try {
                val trimmed = payload.trim()
                if (trimmed.startsWith("[")) {
                    val jsonArray = JSONArray(trimmed)
                    List(jsonArray.length()) { index ->
                        parseRecord(Aidex2Record.fromJson(jsonArray.getJSONObject(index)))
                    }.filterNotNull()
                } else {
                    val jsonObject = JSONObject(trimmed)
                    val nestedArray = firstArray(jsonObject, "data", "records", "items", "cgmData")
                    if (nestedArray != null) {
                        List(nestedArray.length()) { index ->
                            parseRecord(Aidex2Record.fromJson(nestedArray.getJSONObject(index)))
                        }.filterNotNull()
                    } else {
                        listOfNotNull(parseRecord(Aidex2Record.fromJson(jsonObject)))
                    }
                }
            } catch (e: JSONException) {
                aapsLogger.error(LTag.BGSOURCE, "Invalid Aidex2 JSON payload", e)
                emptyList()
            }
        }

        private fun parseRecord(record: Aidex2Record?): GV? {
            record ?: return null
            if (record.status != VALID_STATUS) return null

            val timestamp = normalizeTimestamp(record.timestamp)
            val now = dateUtil.now()
            if (timestamp <= preferences.get(Aidex2LongKey.LastProcessedTimestamp)) return null
            if (timestamp <= 0 || timestamp > now || now - timestamp > T.days(7).msecs()) return null
            val glucose = normalizeGlucose(record.glucose) ?: return null
            if (!isFiveMinuteReading(timestamp)) return null

            return GV(
                timestamp = timestamp,
                value = glucose,
                raw = null,
                noise = null,
                trendArrow = trendFromAidex(record.glucoseTrend ?: 0),
                sourceSensor = SourceSensor.AIDEX
            )
        }

        private fun normalizeTimestamp(timestamp: Long): Long =
            if (timestamp in 1 until 10_000_000_000L) timestamp * 1000 else timestamp

        private fun normalizeGlucose(glucose: Double): Double? =
            when {
                glucose in MIN_MMOL..MAX_MMOL   -> glucose * Constants.MMOLL_TO_MGDL
                glucose in MIN_MGDL..MAX_MGDL   -> glucose
                else                            -> null
            }

        private fun isFiveMinuteReading(timestamp: Long): Boolean {
            val minute = (timestamp / T.mins(1).msecs()) % 60
            return minute % 5 == 0L
        }

        private fun trendFromAidex(trend: Int): TrendArrow =
            when (trend) {
                -3   -> TrendArrow.DOUBLE_DOWN
                -2   -> TrendArrow.SINGLE_DOWN
                -1   -> TrendArrow.FORTY_FIVE_DOWN
                0    -> TrendArrow.FLAT
                1    -> TrendArrow.FORTY_FIVE_UP
                2    -> TrendArrow.SINGLE_UP
                3    -> TrendArrow.DOUBLE_UP
                else -> TrendArrow.NONE
            }

        private fun firstArray(json: JSONObject, vararg keys: String): JSONArray? {
            keys.forEach { key ->
                val value = json.opt(key)
                if (value is JSONArray) return value
            }
            return null
        }

        private data class Aidex2Record(
            val glucose: Double,
            val glucoseTrend: Int?,
            val status: Int,
            val timestamp: Long
        ) {

            companion object {

                fun fromBundle(bundle: Bundle): Aidex2Record? {
                    val glucose = bundle.firstDouble("glucose", "current", "sgv", "value", "glucoseValue") ?: return null
                    val timestamp = bundle.firstLong("timestamp", "date", "time", "datetime", "sampleTime") ?: return null
                    return Aidex2Record(
                        glucose = glucose,
                        glucoseTrend = bundle.firstInt("glucoseTrend", "trend", "trendArrow"),
                        status = bundle.firstInt("status", "state", "valid") ?: VALID_STATUS,
                        timestamp = timestamp
                    )
                }

                fun fromJson(json: JSONObject): Aidex2Record? {
                    val glucose = json.firstDouble("glucose", "current", "sgv", "value", "glucoseValue") ?: return null
                    val timestamp = json.firstLong("timestamp", "date", "time", "datetime", "sampleTime") ?: return null
                    return Aidex2Record(
                        glucose = glucose,
                        glucoseTrend = json.firstInt("glucoseTrend", "trend", "trendArrow")
                            ?: directionToAidexTrend(json.firstString("direction", "trendDirection", "trendArrow")),
                        status = json.firstInt("status", "state", "valid") ?: VALID_STATUS,
                        timestamp = timestamp
                    )
                }

                fun fromObject(value: Any): Aidex2Record? {
                    val glucose = value.firstNumber("glucose", "current", "sgv", "value", "glucoseValue")?.toDouble() ?: return null
                    val timestamp = value.firstNumber("timestamp", "date", "time", "datetime", "sampleTime")?.toLong() ?: return null
                    return Aidex2Record(
                        glucose = glucose,
                        glucoseTrend = value.firstNumber("glucoseTrend", "trend", "trendArrow")?.toInt(),
                        status = value.firstNumber("status", "state", "valid")?.toInt() ?: VALID_STATUS,
                        timestamp = timestamp
                    )
                }

                private fun Any.firstNumber(vararg names: String): Number? {
                    names.forEach { name -> readNumber(name)?.let { return it } }
                    return null
                }

                private fun Any.readNumber(name: String): Number? {
                    val capitalized = name.replaceFirstChar { it.uppercaseChar() }
                    val methodNames = listOf(name, "get$capitalized", "is$capitalized")
                    for (methodName in methodNames) {
                        val method = runCatching { javaClass.getMethod(methodName) }.getOrNull() ?: continue
                        val value = runCatching { method.invoke(this) }.getOrNull()
                        if (value is Number) return value
                    }
                    val field = runCatching { javaClass.getDeclaredField(name) }.getOrNull() ?: return null
                    field.isAccessible = true
                    return runCatching { field.get(this) as? Number }.getOrNull()
                }

                private fun Bundle.firstDouble(vararg keys: String): Double? {
                    keys.forEach { key -> doubleValue(key)?.let { return it } }
                    return null
                }

                private fun Bundle.firstInt(vararg keys: String): Int? {
                    keys.forEach { key -> intValue(key)?.let { return it } }
                    return null
                }

                private fun Bundle.firstLong(vararg keys: String): Long? {
                    keys.forEach { key -> longValue(key)?.let { return it } }
                    return null
                }

                private fun Bundle.doubleValue(key: String): Double? {
                    val value = get(key) ?: return null
                    return when (value) {
                        is Number -> value.toDouble()
                        is String -> value.toDoubleOrNull()
                        else      -> null
                    }
                }

                private fun Bundle.intValue(key: String): Int? {
                    val value = get(key) ?: return null
                    return when (value) {
                        is Number -> value.toInt()
                        is String -> value.toIntOrNull()
                        else      -> null
                    }
                }

                private fun Bundle.longValue(key: String): Long? {
                    val value = get(key) ?: return null
                    return when (value) {
                        is Number -> value.toLong()
                        is String -> value.toLongOrNull()
                        else      -> null
                    }
                }

                private fun JSONObject.firstDouble(vararg keys: String): Double? {
                    keys.forEach { key ->
                        if (has(key) && !isNull(key)) return optDouble(key)
                    }
                    return null
                }

                private fun JSONObject.firstInt(vararg keys: String): Int? {
                    keys.forEach { key ->
                        if (has(key) && !isNull(key)) {
                            val value = opt(key)
                            when (value) {
                                is Number -> return value.toInt()
                                is String -> value.toIntOrNull()?.let { return it }
                            }
                        }
                    }
                    return null
                }

                private fun JSONObject.firstLong(vararg keys: String): Long? {
                    keys.forEach { key ->
                        if (has(key) && !isNull(key)) return optLong(key)
                    }
                    return null
                }

                private fun JSONObject.firstString(vararg keys: String): String? {
                    keys.forEach { key ->
                        if (has(key) && !isNull(key)) return optString(key)
                    }
                    return null
                }

                private fun directionToAidexTrend(direction: String?): Int? =
                    when (TrendArrow.fromString(direction)) {
                        TrendArrow.DOUBLE_DOWN     -> -3
                        TrendArrow.SINGLE_DOWN     -> -2
                        TrendArrow.FORTY_FIVE_DOWN -> -1
                        TrendArrow.FLAT            -> 0
                        TrendArrow.FORTY_FIVE_UP   -> 1
                        TrendArrow.SINGLE_UP       -> 2
                        TrendArrow.DOUBLE_UP       -> 3
                        else                       -> null
                    }
            }
        }
    }

    companion object {

        const val AIDEX2_DATA_KEY = "com.microtech.aidexx.broadcast.CGM_DATA"
        private const val VALID_STATUS = 0
        private const val MIN_MGDL = 40.0
        private const val MAX_MGDL = 400.0
        private const val MIN_MMOL = 2.0
        private const val MAX_MMOL = 25.0
    }
}
