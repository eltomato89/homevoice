package de.homevoice.assistant.media

import android.content.Context

class PrefsSpotifyTokenStore(context: Context) : SpotifyTokenStore {
    private val prefs = context.getSharedPreferences("spotify", Context.MODE_PRIVATE)

    override fun load(): SpotifyTokens? {
        val access = prefs.getString("access", null) ?: return null
        val refresh = prefs.getString("refresh", null) ?: return null
        return SpotifyTokens(access, refresh, prefs.getLong("expires", 0))
    }

    override fun save(tokens: SpotifyTokens?) {
        prefs.edit().apply {
            if (tokens == null) clear() else {
                putString("access", tokens.accessToken)
                putString("refresh", tokens.refreshToken)
                putLong("expires", tokens.expiresAtMillis)
            }
        }.apply()
    }

    /** Display name of the connected account, cached for the status page. */
    var userName: String?
        get() = prefs.getString("user", null)
        set(value) = prefs.edit().putString("user", value).apply()
}
