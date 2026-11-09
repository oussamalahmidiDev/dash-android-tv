package tv.afterglow

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

internal object Network {
    private val base = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val secure = base.newBuilder().followSslRedirects(false).build()

    fun client(allowHttp: Boolean): OkHttpClient = if (allowHttp) base else secure

    fun checked(url: String, allowHttp: Boolean): HttpUrl {
        val parsed = url.httpUrl() ?: throw FeedError("Enter a valid HTTP or HTTPS URL.")
        if (!parsed.isHttps && !allowHttp) throw FeedError("Enable HTTP for this source to use its URL.")
        return parsed
    }

    fun <T> fetch(url: HttpUrl, allowHttp: Boolean, consume: (Response) -> T): T {
        if (!url.isHttps && !allowHttp) throw FeedError("Enable HTTP for this source to use its URL.")
        val call = client(allowHttp).newCall(Request.Builder().url(url).build())
        call.timeout().timeout(60, TimeUnit.SECONDS)
        return call.execute().use { response ->
            if (!response.isSuccessful) throw FeedError("The source returned HTTP ${response.code}.")
            consume(response)
        }
    }
}
