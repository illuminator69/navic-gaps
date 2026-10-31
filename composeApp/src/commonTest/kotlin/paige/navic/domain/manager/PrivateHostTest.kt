package paige.navic.domain.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * B-108: which hosts Android 17's local network permission stands in front of, read from the
 * spelling alone (nothing is resolved), and when a failure is put down to that permission.
 */
class PrivateHostTest {
	@Test
	fun privateHosts() {
		for (host in listOf(
			"10.0.2.2", "10.255.255.255",
			"172.16.0.1", "172.31.255.255",
			"192.168.1.10", "192.168.129.153",
			"169.254.3.3",
			"100.64.0.1", "100.127.255.254",
			"fd00::1", "fc00::1", "fdab:1234::5",
			"fe80::1", "[fe80::1%wlan0]", "febf::1",
			"::ffff:192.168.1.2",
			"nas", "NAS", "nas.local", "printer.local."
		)) {
			assertTrue(isPrivateHost(host), host)
		}
	}

	@Test
	fun notPrivateHosts() {
		for (host in listOf(
			"8.8.8.8", "1.1.1.1",
			"172.15.0.1", "172.32.0.1",
			"100.63.255.255", "100.128.0.1",
			"192.169.1.1", "11.0.0.1",
			"music.example.com", "nas.example.org",
			"127.0.0.1", "localhost", "::1",
			"2001:db8::1", "fec0::1",
			"999.1.1.1", ""
		)) {
			assertFalse(isPrivateHost(host), host)
		}
	}

	@Test
	fun loopbackIsItsOwnThing() {
		assertTrue(isLoopbackHost("127.0.0.1"))
		assertTrue(isLoopbackHost("127.1.2.3"))
		assertTrue(isLoopbackHost("localhost"))
		assertTrue(isLoopbackHost("::1"))
		assertFalse(isLoopbackHost("10.0.2.2"))
		assertFalse(isLoopbackHost("music.example.com"))
	}

	@Test
	fun blockedOnlyWhenTheSystemEnforcesItItIsNotGrantedAndTheHostIsPrivate() {
		assertTrue(localNetworkBlocked(sdk = 37, granted = false, host = "10.0.2.2"))
		assertTrue(localNetworkBlocked(sdk = 38, granted = false, host = "nas"))
		assertTrue(localNetworkBlocked(sdk = 37, granted = false, host = "fd00::1"))
		assertFalse(localNetworkBlocked(sdk = 37, granted = true, host = "10.0.2.2"))
		assertFalse(localNetworkBlocked(sdk = 36, granted = false, host = "192.168.1.2"))
		assertFalse(localNetworkBlocked(sdk = 0, granted = false, host = "192.168.1.2"))
		assertFalse(localNetworkBlocked(sdk = 37, granted = false, host = "8.8.8.8"))
		assertFalse(localNetworkBlocked(sdk = 37, granted = false, host = "music.example.com"))
		assertFalse(localNetworkBlocked(sdk = 37, granted = false, host = "127.0.0.1"))
		assertFalse(localNetworkBlocked(sdk = 37, granted = false, host = null))
	}

	@Test
	fun theHubsHostIsReadOffItsStoredAddress() {
		assertEquals("10.0.2.2", hostOfUrl("ws://10.0.2.2:4899"))
		assertEquals("hub.example.com", hostOfUrl(" wss://hub.example.com/ws "))
		assertEquals(null, hostOfUrl(""))
	}
}
