package fr.groggy.racecontrol.tv.f1tv

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class F1TvEntitlementResponse(
    @param:Json(name = "resultCode") val resultCode: String? = null,
    @param:Json(name = "message") val message: String? = null,
    @param:Json(name = "errorDescription") val errorDescription: String? = null,
    @param:Json(name = "resultObj") val resultObj: ResultObj? = null,
    @param:Json(name = "result") val result: ResultObj? = null,
    @param:Json(name = "entitlementToken") val entitlementToken: String? = null
) {
    @JsonClass(generateAdapter = true)
    data class ResultObj(
        @param:Json(name = "entitlementToken") val entitlementToken: String? = null
    )

    fun token(): String? = entitlementToken ?: resultObj?.entitlementToken ?: result?.entitlementToken
}
