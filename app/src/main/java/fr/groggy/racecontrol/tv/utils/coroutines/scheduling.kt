package fr.groggy.racecontrol.tv.utils.coroutines

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.threeten.bp.Duration

suspend fun CoroutineScope.schedule(duration: Duration, f: suspend () -> Any) {
    while (isActive) {
        f()
        delay(duration.toMillis())
    }
}