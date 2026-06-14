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
import app.aaps.plugins.source.keys.SinocareLongKey
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

class SinocareWorkerTest : TestBaseWithProfile() {

    private lateinit var worker: SinocarePlugin.SinocareWorker
    @Mock lateinit var sinocarePlugin: SinocarePlugin
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var workerParameters: WorkerParameters
    @Mock lateinit var dataWorkerStorage: DataWorkerStorage

    init {
        addInjector { injector ->
            if (injector is SinocarePlugin.SinocareWorker) {
                injector.aapsLogger = aapsLogger
                injector.sinocarePlugin = sinocarePlugin
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
        worker = SinocarePlugin.SinocareWorker(context, workerParameters)
    }

    @Test
    fun `When plugin disabled then return success`() {
        runBlocking {
            whenever(sinocarePlugin.isEnabled()).thenReturn(false)

            val result = worker.doWork()

            Assertions.assertEquals(ListenableWorker.Result.success(workDataOf("Result" to "Plugin not enabled")), result)
            verify(persistenceLayer, never()).insertCgmSourceData(any(), any(), any(), any())
        }
    }

    @Test
    fun `When public JSON array is received then insert data`() {
        val timestamp = now - now % 300000
        runBlocking {
            whenever(sinocarePlugin.isEnabled()).thenReturn(true)
            whenever(preferences.get(SinocareLongKey.LastProcessedTimestamp)).thenReturn(0L)
            whenever(persistenceLayer.insertCgmSourceData(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(Single.just(PersistenceLayer.TransactionResult()))
            val payload = JSONArray()
                .put(
                    JSONObject()
                        .put("value", 6.0)
                        .put("trend", "45")
                        .put("timestamp", timestamp)
                )
                .toString()
            val bundle = BundleMock.mocked().apply {
                putString(SinocarePlugin.SINOCARE_DATA_KEY, payload)
            }
            whenever(dataWorkerStorage.pickupBundle(any())).thenReturn(bundle)

            val result = worker.doWork()

            Assertions.assertEquals(ListenableWorker.Result.success(), result)
            val expectedGv = GV(
                timestamp = timestamp,
                value = 108.0,
                raw = null,
                noise = null,
                trendArrow = TrendArrow.FORTY_FIVE_UP,
                sourceSensor = SourceSensor.SINO
            )
            verify(persistenceLayer).insertCgmSourceData(Sources.Sino, listOf(expectedGv), emptyList(), null)
            verify(preferences).put(SinocareLongKey.LastProcessedTimestamp, timestamp)
        }
    }

    @Test
    fun `When alternate field names are used then insert mgdl data`() {
        val timestamp = now - now % 300000
        runBlocking {
            whenever(sinocarePlugin.isEnabled()).thenReturn(true)
            whenever(preferences.get(SinocareLongKey.LastProcessedTimestamp)).thenReturn(0L)
            whenever(persistenceLayer.insertCgmSourceData(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(Single.just(PersistenceLayer.TransactionResult()))
            val payload = JSONObject()
                .put(
                    "data",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("sgv", 135.0)
                                .put("direction", "Flat")
                                .put("date", timestamp)
                        )
                )
                .toString()
            val bundle = BundleMock.mocked().apply {
                putString(SinocarePlugin.SINOCARE_DATA_KEY, payload)
            }
            whenever(dataWorkerStorage.pickupBundle(any())).thenReturn(bundle)

            val result = worker.doWork()

            Assertions.assertEquals(ListenableWorker.Result.success(), result)
            val expectedGv = GV(
                timestamp = timestamp,
                value = 135.0,
                raw = null,
                noise = null,
                trendArrow = TrendArrow.FLAT,
                sourceSensor = SourceSensor.SINO
            )
            verify(persistenceLayer).insertCgmSourceData(Sources.Sino, listOf(expectedGv), emptyList(), null)
        }
    }

    @Test
    fun `When encrypted payload is received then ignore it`() {
        runBlocking {
            whenever(sinocarePlugin.isEnabled()).thenReturn(true)
            whenever(preferences.get(SinocareLongKey.LastProcessedTimestamp)).thenReturn(0L)
            val bundle = BundleMock.mocked().apply {
                putString(SinocarePlugin.SINOCARE_DATA_KEY, JSONObject().put("ciphertext", "abc").put("signature", "sig").toString())
            }
            whenever(dataWorkerStorage.pickupBundle(any())).thenReturn(bundle)

            val result = worker.doWork()

            Assertions.assertEquals(ListenableWorker.Result.success(), result)
            verify(persistenceLayer, never()).insertCgmSourceData(any(), any(), any(), any())
        }
    }
}
