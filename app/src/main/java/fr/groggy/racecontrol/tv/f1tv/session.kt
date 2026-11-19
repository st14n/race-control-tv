package fr.groggy.racecontrol.tv.f1tv

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import fr.groggy.racecontrol.tv.core.InstantPeriod

@JsonClass(generateAdapter = true)
data class F1TvSessionResponse(
    val resultObj: F1TvSessionResult
)

@JsonClass(generateAdapter = true)
data class F1TvSessionResult(
    val containers: List<F1TvSessionResultContainer>
)

@JsonClass(generateAdapter = true)
data class F1TvSessionResultContainer(
    val id: String,
    val metadata: F1TvSessionMetadata
)

@JsonClass(generateAdapter = true)
data class F1TvSessionMetadata(
    val emfAttributes: F1TvSessionEmfAttributes,
    val title: String,
    val pictureUrl: String?,
    val contentSubtype: String,
    val contentId: String
)

@JsonClass(generateAdapter = true)
data class F1TvSessionEmfAttributes(
    @param:Json(name = "MeetingKey") val meetingKey: String,
    @param:Json(name = "Meeting_Start_Date") val startDate: String?,
    @param:Json(name = "Meeting_End_Date") val endDate: String?,
    @param:Json(name = "Series") val series: String? = null,
    /** "OBC": the session has onboard cameras, i.e. it is a real session (race, sprint, qualifying, practice), not a show */
    @param:Json(name = "OBC") val onboardCameras: Any? = null
)

class F1TvSessionId(val value: String)

data class F1TvSession(
    val id: F1TvSessionId,
    val name: String,
    val eventId: String,
    val pictureUrl: String,
    val largePictureUrl:String,
    val contentId: String,
    val contentSubtype: String,
    val period: InstantPeriod,
    val available: Boolean,
    val images: List<F1TvImageId>,
    val channels: List<F1TvChannelId>,
    /** F1's "Series" of the session: "FORMULA 1", "FORMULA 2", "FORMULA 3", "F1 ACADEMY"... */
    val series: String = "FORMULA 1",
    /** A real session (with onboard cameras), not a show such as the Weekend Warm-Up */
    val hasOnboards: Boolean = false
)

@JsonClass(generateAdapter = true)
data class F1TvFutureSessionResponse(
    val resultObj: F1TvFutureSessionLayoutContainers
)

@JsonClass(generateAdapter = true)
data class F1TvFutureSessionLayoutContainers(
    val containers: List<F1TvLayoutContainer>
)

@JsonClass(generateAdapter = true)
data class F1TvLayoutContainer(
    val layout: String,
    val retrieveItems: F1TvFutureSessionRetrieveItems
)

@JsonClass(generateAdapter = true)
data class F1TvFutureSessionRetrieveItems(
    val resultObj: F1TvFutureSessionResult
)

@JsonClass(generateAdapter = true)
data class F1TvFutureSessionResult(
    val containers: List<F1TvFutureSession>
)

@JsonClass(generateAdapter = true)
data class F1TvFutureSession(
    val eventName: String?,
    val events: List<F1TvFutureSessionEvent>?
)

@JsonClass(generateAdapter = true)
data class F1TvFutureSessionEvent(
    val id: String,
    val metadata: F1TvFutureSessionEventMetadata
)

@JsonClass(generateAdapter = true)
data class F1TvFutureSessionEventMetadata(
    val emfAttributes: F1TvFutureSessionEmfAttributes,
    val title: String,
    val pictureUrl: String?,
    val contentSubtype: String,
    val contentId: String
)

@JsonClass(generateAdapter = true)
data class F1TvFutureSessionEmfAttributes(
    val sessionStartDate: Long,
    val sessionEndDate: Long,
    @param:Json(name = "Series") val series: String
)