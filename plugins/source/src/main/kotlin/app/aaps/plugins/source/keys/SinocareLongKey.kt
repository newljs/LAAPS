package app.aaps.plugins.source.keys

import app.aaps.core.keys.interfaces.LongNonPreferenceKey

enum class SinocareLongKey(
    override val key: String,
    override val defaultValue: Long,
    override val exportable: Boolean = true
) : LongNonPreferenceKey {

    LastProcessedTimestamp("last_processed_sinocare_timestamp", 0)
}
