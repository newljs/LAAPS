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
import app.aaps.plugins.source.keys.SinocareLongKey
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SinocarePlugin @Inject constructor(
    rh: ResourceHelper,
    aapsLogger: AAPSLogger,
    preferences: Preferences
) : AbstractBgSourcePlugin(
    PluginDescription()
        .mainType(PluginType.BGSOURCE)
        .fragmentClass(BGSourceFragment::class.java.name)
        .pluginIcon(app.aaps.core.objects.R.drawable.ic_generic_cgm)
        .preferencesId(PluginDescription.PREFERENCE_SCREEN)
        .pluginName(R.string.sinocare)
        .preferencesVisibleInSimpleMode(false)
        .description(R.string.description_source_sinocare),
    ownPreferences = listOf(SinocareLongKey::class.java),
    aapsLogger, rh, preferences
), BgSource {

    class SinocareWorker(
        context: Context,
        params: WorkerParameters
    ) : LoggingWorker(context, params, Dispatchers.IO) {

        @Inject lateinit var sinocarePlugin: SinocarePlugin
        @Inject lateinit var dataWorkerStorage: DataWorkerStorage
        @Inject lateinit var dateUtil: DateUtil
        @Inject lateinit var persistenceLayer: PersistenceLayer
        @Inject lateinit var preferences: Preferences

        @SuppressLint("CheckResult")
        override suspend fun doWorkAndLog(): Result {
            var ret = Result.success()
            if (!sinocarePlugin.isEnabled()) return Result.success(workDataOf("Result" to "Plugin not enabled"))
            val bundle = dataWorkerStorage.pickupBundle(inputData.getLong(DataWorkerStorage.STORE_KEY, -1))
                ?: return Result.failure(workDataOf("Error" to "missing input data"))

            return try {
                val glucoseValues = parseBundle(bundle)
                    .sortedBy { it.timestamp }
                    .distinctBy { it.timestamp }
                if (glucoseValues.isNotEmpty()) {
                    persistenceLayer.insertCgmSourceData(Sources.Sino, glucoseValues, emptyList(), null)
                        .doOnError { ret = Result.failure(workDataOf("Error" to it.toString())) }
                        .blockingGet()
                    preferences.put(SinocareLongKey.LastProcessedTimestamp, glucoseValues.maxOf { it.timestamp })
                }
                ret
            } catch (e: Exception) {
                aapsLogger.error("Error while processing Sinocare data", e)
                Result.failure(workDataOf("Error" to e.toString()))
            }
        }

        private fun parseBundle(bundle: Bundle): List<GV> {
            val payload = bundle.getString(SINOCARE_DATA_KEY)
                ?: bundle.getString("data")
                ?: bundle.getString("payload")
                ?: bundle.getBundle(SINOCARE_DATA_KEY)
                ?: bundle.getBundle("data")
                ?: bundle.getBundle("payload")
                ?: bundle.get(SINOCARE_DATA_KEY)
                ?: bundle.get("data")
                ?: bundle.get("payload")
                ?: bundle
            return when (payload) {
                is String -> parseJsonPayload(payload)
                is Bundle -> listOfNotNull(parseRecord(SinocareRecord.fromBundle(payload)))
                else      -> emptyList()
            }
        }

        private fun parseJsonPayload(payload: String): List<GV> {
            if (payload.isBlank()) return emptyList()
            return try {
                val trimmed = payload.trim()
                if (trimmed.startsWith("[")) {
                    val jsonArray = JSONArray(trimmed)
                    List(jsonArray.length()) { index ->
                        parseRecord(SinocareRecord.fromJson(jsonArray.getJSONObject(index)))
                    }.filterNotNull()
                } else {
                    val jsonObject = JSONObject(trimmed)
                    if (jsonObject.has("ciphertext")) {
                        aapsLogger.warn(LTag.BGSOURCE, "Encrypted Sinocare payload is not supported by the public broadcast source")
                        emptyList()
                    } else {
                        val nestedArray = firstArray(jsonObject, "data", "entries", "records", "items")
                        if (nestedArray != null) {
                            List(nestedArray.length()) { index ->
                                parseRecord(SinocareRecord.fromJson(nestedArray.getJSONObject(index)))
                            }.filterNotNull()
                        } else {
                            listOfNotNull(parseRecord(SinocareRecord.fromJson(jsonObject)))
                        }
                    }
                }
            } catch (e: JSONException) {
                aapsLogger.error(LTag.BGSOURCE, "Invalid Sinocare JSON payload", e)
                emptyList()
            }
        }

        private fun parseRecord(record: SinocareRecord?): GV? {
            record ?: return null
            val timestamp = normalizeTimestamp(record.timestamp)
            val now = dateUtil.now()
            if (timestamp <= preferences.get(SinocareLongKey.LastProcessedTimestamp)) return null
            if (timestamp <= 0 || timestamp > now || now - timestamp > T.days(7).msecs()) return null
            val glucose = normalizeGlucose(record.glucose) ?: return null

            return GV(
                timestamp = timestamp,
                value = glucose,
                raw = null,
                noise = null,
                trendArrow = trendFromSinocare(record.trend),
                sourceSensor = SourceSensor.SINO
            )
        }

        private fun normalizeTimestamp(timestamp: Long): Long =
            if (timestamp in 1 until 10_000_000_000L) timestamp * 1000 else timestamp

        private fun normalizeGlucose(glucose: Double): Double? =
            when {
                glucose in MIN_MMOL..MAX_MMOL -> glucose * Constants.MMOLL_TO_MGDL
                glucose in MIN_MGDL..MAX_MGDL -> glucose
                else                          -> null
            }

        private fun trendFromSinocare(trend: String?): TrendArrow =
            when (trend) {
                "-180" -> TrendArrow.DOUBLE_DOWN
                "-90"  -> TrendArrow.SINGLE_DOWN
                "-45"  -> TrendArrow.FORTY_FIVE_DOWN
                "0"    -> TrendArrow.FLAT
                "45"   -> TrendArrow.FORTY_FIVE_UP
                "90"   -> TrendArrow.SINGLE_UP
                "180"  -> TrendArrow.DOUBLE_UP
                else   -> TrendArrow.fromString(trend)
            }

        private fun firstArray(json: JSONObject, vararg keys: String): JSONArray? {
            keys.forEach { key ->
                val value = json.opt(key)
                if (value is JSONArray) return value
            }
            return null
        }

        private data class SinocareRecord(
            val glucose: Double,
            val timestamp: Long,
            val trend: String?
        ) {

            companion object {

                fun fromBundle(bundle: Bundle): SinocareRecord? {
                    val glucose = bundle.firstDouble("value", "glucose", "sgv", "current", "glucoseValue") ?: return null
                    val timestamp = bundle.firstLong("detectionStartTime", "timestamp", "date", "time", "datetime") ?: return null
                    return SinocareRecord(
                        glucose = glucose,
                        timestamp = timestamp,
                        trend = bundle.firstString("trend", "direction", "trendArrow")
                    )
                }

                fun fromJson(json: JSONObject): SinocareRecord? {
                    val glucose = json.firstDouble("value", "glucose", "sgv", "current", "glucoseValue") ?: return null
                    val timestamp = json.firstLong("detectionStartTime", "timestamp", "date", "time", "datetime") ?: return null
                    return SinocareRecord(
                        glucose = glucose,
                        timestamp = timestamp,
                        trend = json.firstString("trend", "direction", "trendArrow")
                    )
                }

                private fun Bundle.firstDouble(vararg keys: String): Double? {
                    keys.forEach { key ->
                        when (val value = get(key)) {
                            is Number -> return value.toDouble()
                            is String -> value.toDoubleOrNull()?.let { return it }
                        }
                    }
                    return null
                }

                private fun Bundle.firstLong(vararg keys: String): Long? {
                    keys.forEach { key ->
                        when (val value = get(key)) {
                            is Number -> return value.toLong()
                            is String -> value.toLongOrNull()?.let { return it }
                        }
                    }
                    return null
                }

                private fun Bundle.firstString(vararg keys: String): String? {
                    keys.forEach { key ->
                        val value = get(key)
                        if (value != null) return value.toString()
                    }
                    return null
                }

                private fun JSONObject.firstDouble(vararg keys: String): Double? {
                    keys.forEach { key ->
                        if (has(key) && !isNull(key)) return optDouble(key)
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
            }
        }
    }

    companion object {

        const val SINOCARE_DATA_KEY = "diy.aaps.source.sino.EXTRA_BG"
        private const val MIN_MGDL = 40.0
        private const val MAX_MGDL = 400.0
        private const val MIN_MMOL = 2.0
        private const val MAX_MMOL = 25.0
    }
}
