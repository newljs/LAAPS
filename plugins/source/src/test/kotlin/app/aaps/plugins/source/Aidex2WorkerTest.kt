package app.aaps.plugins.source

import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.utils.receivers.DataWorkerStorage
import app.aaps.plugins.source.keys.Aidex2LongKey
import app.aaps.shared.tests.BundleMock
import app.aaps.shared.tests.TestBaseWithProfile
import io.reactivex.rxjava3.core.Single
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class Aidex2WorkerTest : TestBaseWithProfile() {

    private lateinit var worker: Aidex2Plugin.Aidex2Worker
    @Mock lateinit var aidex2Plugin: Aidex2Plugin
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var workerParameters: WorkerParameters
    @Mock lateinit var dataWorkerStorage: DataWorkerStorage

    init {
        addInjector { injector ->
            if (injector is Aidex2Plugin.Aidex2Worker) {
                injector.aapsLogger = aapsLogger
                injector.aidex2Plugin = aidex2Plugin
                injector.dataWorkerStorage = dataWorkerStorage
                injector.dateUtil = dateUtil
                injector.persistenceLayer = persistenceLayer
                injector.preferences = preferences
            }
        }
    }

    @BeforeEach
    fun setupMock() {
        whenever(workerParameters.inputData).thenReturn(workDataOf(DataWorkerStorage.STORE_KEY to 1L))
        worker = Aidex2Plugin.Aidex2Worker(context, workerParameters)
    }

    @Test
    fun `When plugin disabled then return success`() {
        runBlocking {
            whenever(aidex2Plugin.isEnabled()).thenReturn(false)

            val result = worker.doWork()

            Assertions.assertEquals(ListenableWorker.Result.success(workDataOf("Result" to "Plugin not enabled")), result)
            verify(persistenceLayer, never()).insertCgmSourceData(any(), any(), any(), any())
        }
    }

    @Test
    fun `When Aidex2 JSON array is received then insert filtered data`() {
        val timestamp = now - now % 300000
        runBlocking {
            whenever(aidex2Plugin.isEnabled()).thenReturn(true)
            whenever(preferences.get(Aidex2LongKey.LastProcessedTimestamp)).thenReturn(0L)
            whenever(persistenceLayer.insertCgmSourceData(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(Single.just(PersistenceLayer.TransactionResult()))
            val payload = JSONArray()
                .put(
                    JSONObject()
                        .put("glucose", 150.0)
                        .put("glucoseTrend", -1)
                        .put("status", 0)
                        .put("timestamp", timestamp)
                )
                .put(
                    JSONObject()
                        .put("glucose", 151.0)
                        .put("glucoseTrend", 1)
                        .put("status", 1)
                        .put("timestamp", timestamp + 300000)
                )
                .toString()
            val bundle = BundleMock.mocked().apply {
                putString(Aidex2Plugin.AIDEX2_DATA_KEY, payload)
            }
            whenever(dataWorkerStorage.pickupBundle(any())).thenReturn(bundle)

            val result = worker.doWork()

            Assertions.assertEquals(ListenableWorker.Result.success(), result)
            val expectedGv = GV(
                timestamp = timestamp,
                value = 150.0,
                raw = null,
                noise = null,
                trendArrow = TrendArrow.FORTY_FIVE_DOWN,
                sourceSensor = SourceSensor.AIDEX
            )
            verify(persistenceLayer).insertCgmSourceData(Sources.Aidex, listOf(expectedGv), emptyList(), null)
            verify(preferences).put(Aidex2LongKey.LastProcessedTimestamp, timestamp)
        }
    }

    @Test
    fun `When Aidex2 nested JSON uses alternate fields then insert mmol data`() {
        val timestamp = now - now % 300000
        runBlocking {
            whenever(aidex2Plugin.isEnabled()).thenReturn(true)
            whenever(preferences.get(Aidex2LongKey.LastProcessedTimestamp)).thenReturn(0L)
            whenever(persistenceLayer.insertCgmSourceData(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(Single.just(PersistenceLayer.TransactionResult()))
            val payload = JSONObject()
                .put(
                    "records",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("current", 8.0)
                                .put("direction", "FortyFiveUp")
                                .put("status", 0)
                                .put("date", timestamp)
                        )
                )
                .toString()
            val bundle = BundleMock.mocked().apply {
                putString(Aidex2Plugin.AIDEX2_DATA_KEY, payload)
            }
            whenever(dataWorkerStorage.pickupBundle(any())).thenReturn(bundle)

            val result = worker.doWork()

            Assertions.assertEquals(ListenableWorker.Result.success(), result)
            val expectedGv = GV(
                timestamp = timestamp,
                value = 144.0,
                raw = null,
                noise = null,
                trendArrow = TrendArrow.FORTY_FIVE_UP,
                sourceSensor = SourceSensor.AIDEX
            )
            verify(persistenceLayer).insertCgmSourceData(Sources.Aidex, listOf(expectedGv), emptyList(), null)
        }
    }

    @Test
    fun `When bundle is missing then return failure`() {
        runBlocking {
            whenever(aidex2Plugin.isEnabled()).thenReturn(true)
            whenever(dataWorkerStorage.pickupBundle(1L)).thenReturn(null)

            val result = worker.doWork()

            Assertions.assertEquals(ListenableWorker.Result.failure(workDataOf("Error" to "missing input data")), result)
        }
    }
}
