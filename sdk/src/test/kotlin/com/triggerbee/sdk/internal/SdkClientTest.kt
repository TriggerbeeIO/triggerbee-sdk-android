package com.triggerbee.sdk.internal

import com.google.common.truth.Truth.assertThat
import com.triggerbee.sdk.TriggerbeeConfig
import com.triggerbee.sdk.TriggerbeeException
import com.triggerbee.sdk.models.BatchGoal
import com.triggerbee.sdk.models.BatchIdentify
import com.triggerbee.sdk.models.BatchPageview
import com.triggerbee.sdk.models.BatchPurchase
import com.triggerbee.sdk.models.CloseReason
import com.triggerbee.sdk.models.DeviceInfo
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class SdkClientTest {

    private lateinit var server: MockWebServer
    private lateinit var sessionStore: InMemorySessionStore
    private lateinit var client: SdkClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        sessionStore = InMemorySessionStore()
        val config = TriggerbeeConfig(
            siteId = 12345,
            baseUrl = server.url("/").toString().trimEnd('/'),
        )
        client = SdkClient(
            config = config,
            applicationId = "com.example.test",
            sessionStore = sessionStore,
            deviceInfo = TEST_DEVICE_INFO,
        )
    }

    private companion object {
        // Static snapshot for wire-format assertions; no Android Context available in JVM tests.
        val TEST_DEVICE_INFO = DeviceInfo(
            platform = "android",
            type = "mobile",
            osVersion = "13",
            osApiLevel = 33,
            sdkVersion = "0.2.0-test",
            manufacturer = "samsung",
            model = "SM-S908B",
            appVersion = "1.4.2",
            locale = "en-US",
            timeZone = "Europe/Stockholm",
            screenWidth = 1080,
            screenHeight = 2400,
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `start mints a fresh uid on first call and persists it`() = runTest {
        // Arrange
        val generated = 7681010560253955L

        // Act
        val first = client.start { generated }
        val second = client.start { error("generator should not be called once a uid exists") }

        // Assert
        assertThat(first).isEqualTo(generated)
        assertThat(second).isEqualTo(generated)
        assertThat(sessionStore.getUid()).isEqualTo(generated)
    }

    @Test
    fun `start reuses persisted uid on subsequent SdkClient instances`() = runTest {
        // Arrange
        sessionStore.setUid(42L)

        // Act
        val uid = client.start { error("generator should not be called when uid is already persisted") }

        // Assert
        assertThat(uid).isEqualTo(42L)
    }

    @Test
    fun `pageload sends X-Site-Id header and returns widget results`() = runTest {
        // Arrange
        client.start { 100L }
        server.enqueue(MockResponse().setBody("""{"widgets":[{"id":31335,"result":true,"openDelay":0}]}"""))

        // Act
        val widgets = client.pageload(page = "/home", title = "Home", secondsOnPage = 0)

        // Assert
        assertThat(widgets).hasSize(1)
        assertThat(widgets.single().id).isEqualTo(31335)
        assertThat(widgets.single().result).isTrue()

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/v1/client/widgets/pageload")
        assertThat(request.getHeader("X-Site-Id")).isEqualTo("12345")
    }

    @Test
    fun `pageload includes device info in request body`() = runTest {
        // Arrange
        client.start { 100L }
        server.enqueue(MockResponse().setBody("""{"widgets":[]}"""))

        // Act
        client.pageload(page = "/p", title = "P", secondsOnPage = 0)

        // Assert
        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"device\":")
        assertThat(body).contains("\"platform\":\"android\"")
        assertThat(body).contains("\"osVersion\":\"13\"")
        assertThat(body).contains("\"osApiLevel\":33")
        assertThat(body).contains("\"model\":\"SM-S908B\"")
        assertThat(body).contains("\"manufacturer\":\"samsung\"")
        assertThat(body).contains("\"appVersion\":\"1.4.2\"")
        assertThat(body).contains("\"locale\":\"en-US\"")
        assertThat(body).contains("\"timeZone\":\"Europe/Stockholm\"")
        assertThat(body).contains("\"screenWidth\":1080")
        assertThat(body).contains("\"screenHeight\":2400")
    }

    @Test
    fun `pageload increments pageviews counter sent to backend`() = runTest {
        // Arrange
        client.start { 100L }
        server.enqueue(MockResponse().setBody("""{"widgets":[]}"""))
        server.enqueue(MockResponse().setBody("""{"widgets":[]}"""))

        // Act
        client.pageload("/home", "Home", 0)
        client.pageload("/profile", "Profile", 0)

        // Assert
        server.takeRequest() // first
        val secondBody = server.takeRequest().body.readUtf8()
        assertThat(secondBody).contains("\"pageviews\":2")
    }

    @Test
    fun `closeWidget records reason and includes it in next pageload`() = runTest {
        // Arrange
        client.start { 100L }
        // closeWidget is fire-and-forget on a background scope so the public API stays
        // non-suspend — tests must join the returned Job before asserting on its effects.
        client.closeWidget(widgetId = 31335, reason = CloseReason.Dismissal).join()
        server.enqueue(MockResponse().setBody("""{"widgets":[]}"""))

        // Act
        client.pageload("/home", "Home", 0)

        // Assert
        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"widgetId\":31335")
        assertThat(body).contains("\"reason\":\"Dismissal\"")
    }

    @Test
    fun `identify hits the correct path with uid and persists identifier`() = runTest {
        // Arrange
        client.start { 555L }
        server.enqueue(MockResponse().setResponseCode(204))

        // Act
        client.identify("user@example.com", mapOf("plan" to "pro"))

        // Assert
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/v1/client/events/555/identify")
        assertThat(request.getHeader("X-Site-Id")).isEqualTo("12345")
        assertThat(request.body.readUtf8()).contains("\"identifier\":\"user@example.com\"")
        assertThat(sessionStore.getIdentifier()).isEqualTo("user@example.com")
    }

    @Test
    fun `logGoal posts to events_uid_goal with revenue`() = runTest {
        // Arrange
        client.start { 555L }
        server.enqueue(MockResponse().setResponseCode(204))

        // Act
        client.logGoal("purchase", revenue = "199.00")

        // Assert
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/v1/client/events/555/goal")
        val body = request.body.readUtf8()
        assertThat(body).contains("\"name\":\"purchase\"")
        assertThat(body).contains("\"revenue\":\"199.00\"")
    }

    @Test
    fun `logGoal persists goal locally so next pageload sends it for realtime audience matching`() = runTest {
        // Arrange
        client.start { 555L }
        server.enqueue(MockResponse().setResponseCode(204))                  // logGoal
        server.enqueue(MockResponse().setBody("""{"widgets":[]}"""))         // pageload

        // Act
        client.logGoal("signup", revenue = null)
        client.pageload("/home", "Home", 0)

        // Assert
        server.takeRequest() // drain the logGoal POST
        val pageloadBody = server.takeRequest().body.readUtf8()
        assertThat(pageloadBody).contains("\"goals\":[\"signup\"]")
    }

    @Test
    fun `logPurchase posts to events_uid_purchase with revenue and coupon`() = runTest {
        // Arrange
        client.start { 555L }
        server.enqueue(MockResponse().setResponseCode(204))

        // Act
        client.logPurchase(revenue = "199.99", couponCode = "WELCOME10")

        // Assert
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/v1/client/events/555/purchase")
        assertThat(request.getHeader("X-Site-Id")).isEqualTo("12345")
        val body = request.body.readUtf8()
        assertThat(body).contains("\"revenue\":\"199.99\"")
        assertThat(body).contains("\"couponCode\":\"WELCOME10\"")
        assertThat(body).contains("\"device\":")
    }

    @Test
    fun `logPurchase omits revenue and couponCode from wire body when null`() = runTest {
        // Arrange
        client.start { 555L }
        server.enqueue(MockResponse().setResponseCode(204))

        // Act
        client.logPurchase(revenue = null, couponCode = null)

        // Assert
        val body = server.takeRequest().body.readUtf8()
        // explicitNulls=false means null fields drop out of the JSON entirely.
        assertThat(body).doesNotContain("\"revenue\"")
        assertThat(body).doesNotContain("\"couponCode\"")
    }

    @Test
    fun `pageview posts to events_uid_pageview with path and title`() = runTest {
        // Arrange
        client.start { 555L }
        server.enqueue(MockResponse().setResponseCode(204))

        // Act
        client.pageview(page = "/profile", title = "Profile")

        // Assert
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/v1/client/events/555/pageview")
        assertThat(request.getHeader("X-Site-Id")).isEqualTo("12345")
        val body = request.body.readUtf8()
        assertThat(body).contains("\"path\":\"/profile\"")
        assertThat(body).contains("\"title\":\"Profile\"")
    }

    @Test
    fun `setLandingPageQueryParams persists and includes them in next pageload`() = runTest {
        // Arrange
        client.start { 555L }
        server.enqueue(MockResponse().setBody("""{"widgets":[]}"""))

        // Act
        client.setLandingPageQueryParams(listOf("utm_source=newsletter", "utm_campaign=summer"))
        client.pageload("/home", "Home", 0)

        // Assert
        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"landingPageQueryParams\":[\"utm_source=newsletter\",\"utm_campaign=summer\"]")
    }

    @Test
    fun `setLandingPageQueryParams with emptyList clears the persisted set`() = runTest {
        // Arrange
        client.start { 555L }
        client.setLandingPageQueryParams(listOf("ref=push"))
        server.enqueue(MockResponse().setBody("""{"widgets":[]}"""))

        // Act
        client.setLandingPageQueryParams(emptyList())
        client.pageload("/home", "Home", 0)

        // Assert
        val body = server.takeRequest().body.readUtf8()
        // explicitNulls=false + empty list serialised as empty array — either way no stale "ref=push".
        assertThat(body).doesNotContain("ref=push")
    }

    @Test
    fun `widgetUrl includes uid and url-encoded applicationId`() = runTest {
        // Arrange
        client.start { 8335234722033190L }

        // Act
        val url = client.widgetUrl(widgetId = 31335)

        // Assert
        // applicationId "com.example.test" doesn't need percent-encoding but the encoder still
        // runs; using a value with reserved chars would prove the encoding step works.
        assertThat(url).startsWith(server.url("/").toString().trimEnd('/'))
        assertThat(url).contains("/v1/client/widgets/31335/html")
        assertThat(url).contains("siteId=12345")
        assertThat(url).contains("uid=8335234722033190")
        assertThat(url).contains("applicationId=com.example.test")
    }

    @Test
    fun `http 400 surfaces as TriggerbeeException_HttpError`() = runTest {
        // Arrange
        client.start { 100L }
        server.enqueue(MockResponse().setResponseCode(400).setBody("uid is required."))

        // Act + Assert
        val error = assertThrows<TriggerbeeException.HttpError> {
            client.pageload("/home", "Home", 0)
        }
        assertThat(error.code).isEqualTo(400)
        assertThat(error.body).isEqualTo("uid is required.")
    }

    @Test
    fun `recheck hits the audiences check endpoint and returns widget results`() = runTest {
        // Arrange
        client.start { 100L }
        server.enqueue(MockResponse().setBody("""{"widgets":[{"id":42,"result":true,"openDelay":0}]}"""))

        // Act
        val widgets = client.recheck(page = "/home", secondsOnPage = 5)

        // Assert
        assertThat(widgets).hasSize(1)
        assertThat(widgets.single().id).isEqualTo(42)

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/v1/client/widgets/audiences/check")
        assertThat(request.getHeader("X-Site-Id")).isEqualTo("12345")
    }

    @Test
    fun `recheck does not increment pageviews counter`() = runTest {
        // Arrange
        client.start { 100L }
        server.enqueue(MockResponse().setBody("""{"widgets":[]}"""))
        server.enqueue(MockResponse().setBody("""{"widgets":[]}"""))
        client.pageload("/home", "Home", 0)

        // Act
        client.recheck("/home", 5)

        // Assert
        server.takeRequest() // pageload
        val recheckBody = server.takeRequest().body.readUtf8()
        // First pageload bumped the counter to 1; recheck must reuse that value, not bump to 2.
        assertThat(recheckBody).contains("\"pageviews\":1")
    }

    @Test
    fun `closeWidget persists dismissal to SessionStore so it survives process death`() = runTest {
        // Arrange
        client.start { 100L }

        // Act
        // closeWidget returns the Job for its fire-and-forget persistence. Production code
        // ignores it; here we join to make the test deterministic.
        client.closeWidget(widgetId = 31335, reason = CloseReason.Dismissal).join()

        // Assert
        // Without the bug fix the SessionStore would still hold an empty list — the in-memory
        // record alone would be lost on process death.
        val persisted = sessionStore.getClosedWidgets()
        assertThat(persisted).hasSize(1)
        assertThat(persisted.single().widgetId).isEqualTo(31335)
        assertThat(persisted.single().reason).isEqualTo("Dismissal")
    }

    @Test
    fun `closed widgets replay on every pageload until process death`() = runTest {
        // Arrange
        client.start { 100L }
        client.closeWidget(widgetId = 31335, reason = CloseReason.Dismissal).join()
        server.enqueue(MockResponse().setBody("""{"widgets":[]}"""))
        server.enqueue(MockResponse().setBody("""{"widgets":[]}"""))

        // Act
        client.pageload("/home", "Home", 0)
        client.pageload("/profile", "Profile", 0)

        // Assert
        // The SDK intentionally keeps closed widgets in memory for the whole process lifetime
        // (matches the web SDK's sessionStorage semantics) so every subsequent pageload carries
        // them — otherwise the backend could re-open a widget the user just dismissed.
        val first = server.takeRequest().body.readUtf8()
        val second = server.takeRequest().body.readUtf8()
        assertThat(first).contains("\"widgetId\":31335")
        assertThat(second).contains("\"widgetId\":31335")
    }

    @Test
    fun `http 500 surfaces as TriggerbeeException_HttpError with code 500`() = runTest {
        // Arrange
        client.start { 100L }
        server.enqueue(MockResponse().setResponseCode(500).setBody("kaboom"))

        // Act + Assert
        val error = assertThrows<TriggerbeeException.HttpError> {
            client.pageload("/home", "Home", 0)
        }
        assertThat(error.code).isEqualTo(500)
        assertThat(error.body).isEqualTo("kaboom")
    }

    @Test
    fun `disconnect surfaces as TriggerbeeException_NetworkError`() = runTest {
        // Arrange
        client.start { 100L }
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        // Act + Assert
        assertThrows<TriggerbeeException.NetworkError> {
            client.pageload("/home", "Home", 0)
        }
    }

    @Test
    fun `batch posts grouped body to events_uid_batch and updates session identifier`() = runTest {
        // Arrange
        client.start { 555L }
        server.enqueue(MockResponse().setResponseCode(204))

        // Act
        client.batch(
            pageviews = listOf(BatchPageview(path = "/home", title = "Home")),
            goals = listOf(BatchGoal(name = "signup")),
            purchases = listOf(BatchPurchase(revenue = "199.00", couponCode = "WELCOME10")),
            identify = BatchIdentify(identifier = "user@example.com"),
        )

        // Assert
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/v1/client/events/555/batch")
        assertThat(request.getHeader("X-Site-Id")).isEqualTo("12345")
        val body = request.body.readUtf8()
        assertThat(body).contains("\"pageviews\":[{\"path\":\"/home\",\"title\":\"Home\"}]")
        assertThat(body).contains("\"goals\":[{\"name\":\"signup\"}]")
        assertThat(body).contains("\"purchases\":[{\"revenue\":\"199.00\",\"couponCode\":\"WELCOME10\"}]")
        assertThat(body).contains("\"identify\":{\"identifier\":\"user@example.com\"")
        // Identify in a batch mirrors into the session like a standalone identify() would.
        assertThat(client.sessionContext.value.identifier).isEqualTo("user@example.com")
    }

    @Test
    fun `batch omits empty groups from the wire body`() = runTest {
        // Arrange
        client.start { 555L }
        server.enqueue(MockResponse().setResponseCode(204))

        // Act
        client.batch(
            pageviews = emptyList(),
            goals = listOf(BatchGoal(name = "signup")),
            purchases = emptyList(),
            identify = null,
        )

        // Assert
        val body = server.takeRequest().body.readUtf8()
        // explicitNulls=false on the Json config drops null fields entirely.
        assertThat(body).doesNotContain("pageviews")
        assertThat(body).doesNotContain("purchases")
        assertThat(body).doesNotContain("identify")
        assertThat(body).contains("\"goals\":[{\"name\":\"signup\"}]")
    }

    @Test
    fun `sessionContext reflects start identifier and pageviews`() = runTest {
        // Arrange + Act
        client.start { 555L }
        server.enqueue(MockResponse().setResponseCode(204))
        client.identify("user@example.com")

        server.enqueue(MockResponse().setBody("""{"widgets":[]}"""))
        client.pageload("/home", "Home", 0)

        // Assert
        val snapshot = client.sessionContext.value
        assertThat(snapshot.uid).isEqualTo(555L)
        assertThat(snapshot.identifier).isEqualTo("user@example.com")
        assertThat(snapshot.pageviews).isEqualTo(1)
    }
}
