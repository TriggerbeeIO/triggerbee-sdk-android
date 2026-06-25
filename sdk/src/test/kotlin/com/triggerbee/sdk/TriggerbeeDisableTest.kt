package com.triggerbee.sdk

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric is the only way to exercise the [Triggerbee] singleton's `disable()` /
 * `enable()` opt-out — `init()` needs a real Android `Context` to build the
 * `DataStoreSessionStore` and `DeviceInfoCollector`. All other public methods are
 * covered indirectly via [com.triggerbee.sdk.internal.SdkClientTest] on the JVM, but
 * the opt-out gate lives one layer above `SdkClient` so this is its only test surface.
 */
@RunWith(RobolectricTestRunner::class)
public class TriggerbeeDisableTest {

    private lateinit var server: MockWebServer

    @Before
    public fun setUp() {
        server = MockWebServer().apply { start() }
        Triggerbee.enable() // reset across tests — the singleton's flag survives JVM-wide
        Triggerbee.init(
            context = ApplicationProvider.getApplicationContext(),
            config = TriggerbeeConfig(
                siteId = 12345,
                baseUrl = server.url("/").toString().trimEnd('/'),
            ),
        )
    }

    @After
    public fun tearDown() {
        Triggerbee.enable()
        server.shutdown()
    }

    @Test
    public fun `disable makes suspend calls a no-op and prevents network requests`(): Unit = runBlocking {
        // Arrange
        Triggerbee.disable()

        // Act — none of these should hit the server
        Triggerbee.start()
        val widgets = Triggerbee.pageload(page = "/home", title = "Home")
        Triggerbee.logGoal("signup", null)
        Triggerbee.logPurchase(revenue = "199.00", couponCode = null)
        Triggerbee.pageview(page = "/profile", title = "Profile")
        Triggerbee.identify("user@example.com")
        Triggerbee.setLandingPageQueryParams(listOf("utm_source=test"))

        // Assert
        assertThat(widgets).isEmpty()
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(Triggerbee.isDisabled).isTrue()
    }

    @Test
    public fun `widgetUrl returns empty string when disabled`() {
        // Arrange
        Triggerbee.disable()

        // Act
        val url = Triggerbee.widgetUrl(widgetId = 31335)

        // Assert
        assertThat(url).isEmpty()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    public fun `enable after disable restores normal behaviour`(): Unit = runBlocking {
        // Arrange
        Triggerbee.disable()
        Triggerbee.start() // no-op
        Triggerbee.enable()
        server.enqueue(MockResponse().setBody("""{"widgets":[]}"""))

        // Act
        Triggerbee.start()
        Triggerbee.pageload("/home", "Home")

        // Assert
        assertThat(Triggerbee.isDisabled).isFalse()
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(Triggerbee.sessionContext.value.uid).isNotEqualTo(0L)
    }
}
