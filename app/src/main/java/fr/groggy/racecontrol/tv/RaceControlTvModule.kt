package fr.groggy.racecontrol.tv

import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.groggy.racecontrol.tv.utils.DeviceInfo
import android.util.Log
import okhttp3.Interceptor
import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import org.threeten.bp.Clock
import java.net.CookieManager
import java.net.CookiePolicy
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
class RaceControlTvModule {

    @Provides
    @Singleton
    fun clock(): Clock =
        Clock.systemUTC()

    @Provides
    @Singleton
    fun cookieManager(): CookieManager {
        val manager = CookieManager()
        manager.setCookiePolicy(CookiePolicy.ACCEPT_ORIGINAL_SERVER)
        return manager
    }

    @Provides
    fun loggingInterceptor(): HttpLoggingInterceptor {
        return if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC)
        } else {
            HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.NONE)
        }
    }

    @Provides
    @Singleton
    fun okHttpClient(
        cookieManager: CookieManager,
        loggingInterceptor: HttpLoggingInterceptor
    ): OkHttpClient =
        OkHttpClient.Builder()
            .cookieJar(JavaNetCookieJar(cookieManager))
            .addInterceptor(loggingInterceptor)
            .addInterceptor(PlaybackManifestHttpLoggingInterceptor())
            .build()

    @Provides
    @Singleton
    fun moshi(): Moshi =
        Moshi.Builder().build()

    @Provides
    @Singleton
    fun httpDataSourceFactory(okHttpClient: OkHttpClient): HttpDataSource.Factory {
        return OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(DeviceInfo.userAgent)
            .setDefaultRequestProperties(
                mapOf(
                    "x-f1-device-info" to DeviceInfo.f1DeviceInfo
                    // Cookies are handled by OkHttp's CookieJar
                )
            )
    }
}

class PlaybackManifestHttpLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        val isManifestOrDrm = url.contains(".m3u8", ignoreCase = true) ||
                url.contains(".mpd", ignoreCase = true) ||
                url.contains("license", ignoreCase = true) ||
                url.contains("drm", ignoreCase = true) ||
                request.header("ascendonToken") != null

        if (!isManifestOrDrm) {
            return chain.proceed(request)
        }

        val cleanUrl = request.url.newBuilder().query(null).build().toString()
        Log.i("PlaybackManifestHttp", "--> SENDING REQUEST: ${request.method} $cleanUrl")
        Log.d("PlaybackManifestHttp", "Request Headers:\n${request.headers}")

        val startTime = System.nanoTime()
        val response = try {
            chain.proceed(request)
        } catch (e: Exception) {
            Log.e("PlaybackManifestHttp", "<-- REQUEST FAILED: ${request.method} $cleanUrl: ${e.message}", e)
            throw e
        }
        val durationMs = (System.nanoTime() - startTime) / 1_000_000.0

        Log.i("PlaybackManifestHttp", "<-- RECEIVED RESPONSE: ${response.code} (${String.format(java.util.Locale.US, "%.1f", durationMs)}ms) for ${request.method} $cleanUrl")
        Log.d("PlaybackManifestHttp", "Response Headers:\n${response.headers}")

        val responseBody = response.body
        if (responseBody != null) {
            val contentType = responseBody.contentType()
            val isText = contentType?.let {
                val subtype = it.subtype.lowercase(java.util.Locale.US)
                subtype.contains("text") ||
                subtype.contains("json") ||
                subtype.contains("xml") ||
                subtype.contains("mpegurl") ||
                subtype.contains("dash+xml")
            } ?: false

            if (isText || url.contains(".m3u8", ignoreCase = true) || url.contains(".mpd", ignoreCase = true) || url.contains("license", ignoreCase = true) || url.contains("drm", ignoreCase = true)) {
                val source = responseBody.source()
                source.request(Long.MAX_VALUE)
                val buffer = source.buffer
                val charset = contentType?.charset(java.nio.charset.StandardCharsets.UTF_8) ?: java.nio.charset.StandardCharsets.UTF_8
                if (responseBody.contentLength() != 0L) {
                    val bodyText = buffer.clone().readString(charset)
                    val logLimit = 1500
                    val preview = if (bodyText.length > logLimit) {
                        bodyText.substring(0, logLimit) + "\n... [TRUNCATED ${bodyText.length - logLimit} CHARS]"
                    } else {
                        bodyText
                    }
                    Log.d("PlaybackManifestHttp", "Response Body Preview:\n$preview")
                } else {
                    Log.d("PlaybackManifestHttp", "Response Body is empty")
                }
            }
        }

        return response
    }
}
