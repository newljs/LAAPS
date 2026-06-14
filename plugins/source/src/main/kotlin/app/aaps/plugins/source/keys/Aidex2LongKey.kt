package app.aaps.plugins.source.keys

import app.aaps.core.keys.interfaces.LongNonPreferenceKey

enum class Aidex2LongKey(
    override val key: String,
    override val defaultValue: Long,
    override val exportable: Boolean = true
) : LongNonPreferenceKey {

    LastProcessedTimestamp("last_processed_aidex2_timestamp", 0)
}
