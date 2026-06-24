package app.aaps.plugins.source

import android.content.Context
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.utils.receivers.DataWorkerStorage
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.mock

class Aidex2PluginTest : TestBase() {

    private lateinit var aidex2Plugin: Aidex2Plugin

    @Mock lateinit var rh: ResourceHelper
    @Mock lateinit var preferences: Preferences
    @Mock lateinit var context: Context
    @Mock lateinit var aidex2BroadcastReceiver: Aidex2BroadcastReceiver

    @BeforeEach
    fun setup() {
        aidex2Plugin = Aidex2Plugin(rh, aapsLogger, preferences, context, aidex2BroadcastReceiver)
    }

    @Test fun advancedFilteringSupported() {
        assertThat(aidex2Plugin.advancedFilteringSupported()).isFalse()
    }
}
