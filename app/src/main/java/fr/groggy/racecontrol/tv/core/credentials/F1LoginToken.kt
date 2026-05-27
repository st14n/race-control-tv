package fr.groggy.racecontrol.tv.core.credentials

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class F1LoginToken(
    @param:Json(name = "data") val data: F1TokenData
)

@JsonClass(generateAdapter = true)
data class F1TokenData(
    @param:Json(name = "subscriptionToken") val subscriptionToken: String
)