package paige.navic.domain.manager

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import java.util.concurrent.TimeUnit

/**
 * How often a hub WebSocket pings, and how long it waits for the pong before OkHttp fails the
 * socket (OkHttp uses the one interval for both).
 */
private const val WS_PING_INTERVAL_S = 10L

/**
 * An HttpClient for a hub WebSocket whose protocol pings really go out (B-045).
 *
 * `install(WebSockets) { pingIntervalMillis = … }` sends nothing on this engine: Ktor's OkHttp
 * session only reports the interval of the OkHttpClient underneath, which is 0 by default, so no
 * ping ever left and a half-open socket (Wi-Fi to mobile, a NAT timeout, a doze) blocked
 * `incoming` until something else noticed. Set on the engine, OkHttp pings every
 * [WS_PING_INTERVAL_S] and fails the socket when a pong does not come back in time; the reconnect
 * loop then takes over.
 *
 * Used by [HubManager] (through [HubSocketClientFactory]) and by every cast bridge's socket.
 */
fun pingingWebSocketClient(): HttpClient = HttpClient(OkHttp) {
	engine {
		config { pingInterval(WS_PING_INTERVAL_S, TimeUnit.SECONDS) }
	}
	install(WebSockets)
}
