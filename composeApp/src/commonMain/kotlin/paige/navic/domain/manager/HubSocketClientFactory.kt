package paige.navic.domain.manager

import io.ktor.client.HttpClient

/**
 * Builds the HttpClient [HubManager]'s hub WebSocket rides on, with WebSockets installed and
 * transport-level pings that really go out (B-045).
 *
 * Platform-provided because only the engine can send those pings: Ktor's
 * `WebSockets { pingIntervalMillis }` is a no-op on the OkHttp engine, whose session merely
 * reports the interval its own OkHttpClient was built with, and that is 0 unless configured. So
 * Android builds an OkHttp client with `pingInterval` set (`pingingWebSocketClient`), and iOS a
 * default one. Same shape as [CastBridgeStatus]: an interface here, one Koin registration in each
 * `PlatformModule`, rather than an `expect`.
 */
fun interface HubSocketClientFactory {
	fun create(): HttpClient
}
