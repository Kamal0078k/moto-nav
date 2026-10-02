package dev.motonav.simulator.routing

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder

fun interface RedirectFetcher {
    /** Returns the redirect target for [url], or null when the response is not a redirect. */
    @Throws(IOException::class)
    fun redirectTarget(url: String): String?
}

/**
 * Expands maps.app.goo.gl share links by following plain HTTP redirects. This is an ordinary
 * web request, not a Google Maps Platform API call, so it is free and needs no key.
 */
class ShortLinkResolver(
    private val fetcher: RedirectFetcher = UrlConnectionRedirectFetcher(),
    private val maxHops: Int = 6,
) {
    @Throws(IOException::class)
    fun resolve(url: String): String {
        var current = url
        repeat(maxHops) {
            unwrapConsent(current)?.let { current = it }
            if (!GoogleMapsLink.isShortLink(current) && GoogleMapsLink.isGoogleMapsUrl(current)) return current
            val next = fetcher.redirectTarget(current) ?: return current
            current = URI(current).resolve(next.replace(" ", "%20")).toString()
        }
        return current
    }

    private fun unwrapConsent(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.host?.startsWith("consent.") != true) return null
        return uri.rawQuery.orEmpty().split('&')
            .firstOrNull { it.startsWith("continue=") }
            ?.removePrefix("continue=")
            ?.let { URLDecoder.decode(it, "UTF-8") }
    }
}

class UrlConnectionRedirectFetcher(private val timeoutMillis: Int = 8_000) : RedirectFetcher {
    override fun redirectTarget(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = timeoutMillis
            readTimeout = timeoutMillis
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) MotoNav")
        }
        try {
            val status = connection.responseCode
            if (status in 300..399) return connection.getHeaderField("Location")
            if (status !in 200..299) throw IOException("Link could not be opened (HTTP $status)")
            // Some short links answer 200 with the destination embedded in the page.
            val body = connection.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(64 * 1024)
                val read = reader.read(buffer)
                if (read <= 0) "" else String(buffer, 0, read)
            }
            return Regex("""https://www\.google\.[a-z.]+/maps/[^"'\s<>\\]+""").find(body)?.value
        } finally {
            connection.disconnect()
        }
    }
}
