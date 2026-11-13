package com.liskovsoft.youtubeapi.videoinfo.V2
import java.security.MessageDigest

/** Credentials captured by a host-owned sign-in flow; never persisted or logged by the core. */
class WebSession(val cookies: String, val accountIndex: Int = 0) {
    init { require(accountIndex >= 0); require(!cookies.contains('\r') && !cookies.contains('\n')) }
    private fun cookie(name: String) = cookies.split(';').map { it.trim() }.firstOrNull { it.startsWith(name + "=") }
        ?.substringAfter('=')?.takeIf { it.isNotEmpty() }
    val signedIn: Boolean get() = secret() != null
    private fun secret() = cookie("SAPISID") ?: cookie("__Secure-3PAPISID") ?: cookie("__Secure-1PAPISID")
    fun headers(nowSeconds: Long = System.currentTimeMillis() / 1000): Map<String, String> {
        val sapisid = secret() ?: return emptyMap()
        val origin = "https://www.youtube.com"
        val hash = MessageDigest.getInstance("SHA-1")
            .digest("$nowSeconds $sapisid $origin".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return mapOf("Cookie" to cookies, "Authorization" to "SAPISIDHASH ${nowSeconds}_$hash",
            "X-Goog-AuthUser" to accountIndex.toString(), "X-Origin" to origin, "Origin" to origin)
    }
    internal fun scope() = textDigest(cookies) + ":" + accountIndex
    override fun toString() = "WebSession(accountIndex=$accountIndex)"
}
