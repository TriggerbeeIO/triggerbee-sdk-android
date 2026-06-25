package com.triggerbee.sdk.internal

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Retrofit interface for PublicApiGateway's client-to-server endpoints. All routes live under
 * the `/v1/client/` prefix and are authenticated by the `X-Application-Id` header (matched
 * against the account's `AllowedApplicationIds`). The header is added globally by an OkHttp
 * interceptor in [SdkClient.buildApi], so individual methods don't declare it.
 */
internal interface TriggerbeeApi {

    @POST("/v1/client/widgets/pageload")
    suspend fun pageload(
        @Header("X-Site-Id") siteId: Long,
        @Body request: PageloadRequest,
    ): CheckWidgetsResponse

    @POST("/v1/client/widgets/audiences/check")
    suspend fun check(
        @Header("X-Site-Id") siteId: Long,
        @Body request: CheckRequest,
    ): CheckWidgetsResponse

    @POST("/v1/client/events/{uid}/identify")
    suspend fun identify(
        @Header("X-Site-Id") siteId: Long,
        @Path("uid") uid: Long,
        @Body request: IdentifyRequest,
    )

    @POST("/v1/client/events/{uid}/goal")
    suspend fun goal(
        @Header("X-Site-Id") siteId: Long,
        @Path("uid") uid: Long,
        @Body request: GoalRequest,
    )

    @POST("/v1/client/events/{uid}/pageview")
    suspend fun pageview(
        @Header("X-Site-Id") siteId: Long,
        @Path("uid") uid: Long,
        @Body request: PageviewRequest,
    )

    @POST("/v1/client/events/{uid}/purchase")
    suspend fun purchase(
        @Header("X-Site-Id") siteId: Long,
        @Path("uid") uid: Long,
        @Body request: PurchaseRequest,
    )

    @POST("/v1/client/events/{uid}/batch")
    suspend fun batch(
        @Header("X-Site-Id") siteId: Long,
        @Path("uid") uid: Long,
        @Body request: BatchRequest,
    )
}
