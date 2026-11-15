package nl.giejay.android.tv.immich.api.util

import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Resolves like [Dns.SYSTEM] but uses only the IPv4 addresses when there are any.
 *
 * KitKat has no "happy eyeballs": OkHttp tries addresses in resolver order, and many home networks
 * advertise an IPv6 default route with only local (ULA) addresses, so the IPv6 connect to a public
 * host just times out (and OkHttp 3.12 then reports that timeout, hiding the real IPv4 error).
 * IPv6 is still used when it's the only option.
 */
object Ipv4FirstDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val resolved = Dns.SYSTEM.lookup(hostname)
        val ipv4 = resolved.filterIsInstance<Inet4Address>()
        return ipv4.ifEmpty { resolved }
    }
}
