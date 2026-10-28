package paige.navic.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModuleCollector
import paige.navic.domain.manager.SourceHistorySection
import paige.navic.ui.screens.downloads.DownloadsSection
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * Audit L2-08: what a back stack saved by one build must still mean to the next. Every screen the
 * real configuration ([navSavedStateConfiguration]) knows round-trips; the enums a screen carries are
 * saved by position, so their order is pinned here (appending means editing the literal: that is
 * the point). A renamed screen fails the round trip's lookup; the fix is a `@SerialName` alias.
 * JVM-only (reflection on the screen classes), so androidHostTest. The round trip goes through JSON
 * with the configuration's own serializers module: a host test's Bundle is a stub that throws, so
 * the SavedState encoder's format itself is not exercised here, only the registrations it reads.
 */
class SavedStackGuardTest {

	/** Every subclass registered under [NavKey] in the real configuration. */
	@OptIn(ExperimentalSerializationApi::class)
	private fun registeredScreens(): List<KClass<*>> {
		val found = mutableListOf<KClass<*>>()
		navSavedStateConfiguration.serializersModule.dumpTo(object : SerializersModuleCollector {
			override fun <T : Any> contextual(kClass: KClass<T>, provider: (List<KSerializer<*>>) -> KSerializer<*>) {}
			override fun <Base : Any, Sub : Base> polymorphic(
				baseClass: KClass<Base>,
				actualClass: KClass<Sub>,
				actualSerializer: KSerializer<Sub>
			) {
				if (baseClass == NavKey::class) found += actualClass
			}
			override fun <Base : Any> polymorphicDefaultSerializer(
				baseClass: KClass<Base>,
				defaultSerializerProvider: (Base) -> SerializationStrategy<Base>?
			) {}
			override fun <Base : Any> polymorphicDefaultDeserializer(
				baseClass: KClass<Base>,
				defaultDeserializerProvider: (String?) -> DeserializationStrategy<Base>?
			) {}
		})
		return found
	}

	/** The object, or the value its all-defaults constructor makes; null when it needs arguments. */
	private fun instanceOf(kClass: KClass<*>): NavKey? {
		val java = kClass.java
		runCatching { return java.getField("INSTANCE").get(null) as NavKey }
		return runCatching { java.getDeclaredConstructor().newInstance() as NavKey }.getOrNull()
	}

	@Test
	fun everyConcreteScreenRoundTrips() {
		val screens = registeredScreens()
		assertTrue(screens.size > 20, "the configuration registered only ${screens.size} screens")
		val serializer = PolymorphicSerializer(NavKey::class)
		val json = Json { serializersModule = navSavedStateConfiguration.serializersModule }
		var checked = 0
		screens.forEach { kClass ->
			val value = instanceOf(kClass) ?: return@forEach
			val saved = json.encodeToString(serializer, value)
			assertEquals(value, json.decodeFromString(serializer, saved), "$kClass")
			checked++
		}
		assertTrue(checked > 20, "only $checked screens could be built without arguments")
	}

	@Test
	fun enumsSavedByPositionKeepTheirOrder() {
		assertEquals(listOf("Device", "Fills", "Wishlist"), DownloadsSection.entries.map { it.name })
		assertEquals(listOf("Requests", "Transfers", "Wishlist"), SourceHistorySection.entries.map { it.name })
	}
}
