package fr.groggy.racecontrol.tv.utils.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import java.io.IOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class CoroutineCallback(private val continuation: Continuation<Response>) : Callback {
    override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)
    override fun onResponse(call: Call, response: Response) = continuation.resume(response)
}

suspend fun Request.execute(httpClient: OkHttpClient): Response {
    val call = httpClient.newCall(this)
    return withContext(Dispatchers.IO) {
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            // Leaving the screen cancels the request instead of letting it run on
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) continuation.resume(response) else response.close()
                }
            })
        }
    }
}