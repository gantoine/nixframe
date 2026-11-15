package nl.giejay.android.tv.immich.api.util

import com.google.common.truth.Truth.assertThat
import okhttp3.Call
import okhttp3.Request
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

class HostRoutingCallFactoryTest {
    private val immich = mock<Call.Factory> { on { newCall(any()) } doReturn mock() }
    private val public = mock<Call.Factory> { on { newCall(any()) } doReturn mock() }

    private fun route(url: String, immichHost: String?) {
        HostRoutingCallFactory(immich, public) { immichHost }.newCall(Request.Builder().url(url).build())
    }

    @Test
    fun `requests to the Immich host use the authenticated client`() {
        route("https://photos.example.com/api/assets/1/thumbnail", "photos.example.com")
        verify(immich).newCall(any())
        verify(public, never()).newCall(any())
    }

    @Test
    fun `requests to other hosts never get the Immich API key`() {
        route("https://images.unsplash.com/photo-1?w=1280", "photos.example.com")
        verify(public).newCall(any())
        verify(immich, never()).newCall(any())
    }

    @Test
    fun `host comparison ignores case`() {
        route("https://Photos.Example.com/api/x", "photos.example.com")
        verify(immich).newCall(any())
    }

    @Test
    fun `unknown Immich host keeps the previous behaviour`() {
        route("https://images.unsplash.com/photo-1", null)
        verify(immich).newCall(any())
    }

    @Test
    fun `hostOf extracts the host from a server URL`() {
        assertThat(HostRoutingCallFactory.hostOf("https://photos.example.com:2283")).isEqualTo("photos.example.com")
        assertThat(HostRoutingCallFactory.hostOf("http://192.168.2.10:2283/")).isEqualTo("192.168.2.10")
        assertThat(HostRoutingCallFactory.hostOf("")).isNull()
        assertThat(HostRoutingCallFactory.hostOf("not a url")).isNull()
    }
}
