package com.alikarami.drooz.player

import android.content.Intent
import com.alikarami.drooz.site.model.TitleRef

/** What the player activity is started with. */
data class PlayerArgs(val ref: TitleRef, val streamId: String, val dub: Boolean) {
    fun toIntent(intent: Intent): Intent = intent
        .putExtra(EXTRA_REF_PATH, ref.path)
        .putExtra(EXTRA_STREAM_ID, streamId)
        .putExtra(EXTRA_DUB, dub)

    /** Intent-free form of [toIntent] (Intent extras are no-ops in JVM tests). */
    fun toExtras(): Map<String, Any> = mapOf(EXTRA_REF_PATH to ref.path, EXTRA_STREAM_ID to streamId, EXTRA_DUB to dub)

    companion object {
        const val EXTRA_REF_PATH = "ref_path"
        const val EXTRA_STREAM_ID = "stream_id"
        const val EXTRA_DUB = "dub"

        /** Null when extras are missing or invalid. */
        fun from(intent: Intent): PlayerArgs? = fromExtras { key ->
            when {
                !intent.hasExtra(key) -> null
                key == EXTRA_DUB -> intent.getBooleanExtra(key, false)
                else -> intent.getStringExtra(key)
            }
        }

        fun fromExtras(get: (String) -> Any?): PlayerArgs? {
            val ref = (get(EXTRA_REF_PATH) as? String)?.let(TitleRef::fromPath) ?: return null
            val streamId = (get(EXTRA_STREAM_ID) as? String)?.takeIf { it.isNotBlank() } ?: return null
            val dub = get(EXTRA_DUB) as? Boolean ?: return null
            return PlayerArgs(ref, streamId, dub)
        }
    }
}
