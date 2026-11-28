package com.tunisianprayertimes.quran.assets

import android.app.Application
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackStates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TafsirPlayManagerTest {
    @Test
    fun allFiveRequestsReachThePlaySdkAndOnlyTheChosenTransferIsCancelled() {
        val layout = QuranPackLayout("https://example.invalid/", QuranPackLayout.tafsirTitles.keys.map { id ->
            val name = "quran_tafsir_$id"
            QuranPack(name, QuranPack.Kind.Tafsir, QuranPack.Delivery.Play, null, null, 10,
                QuranPackArchive("v1/packs/$name-aaaaaaaaaaaa.zip", 100, "a".repeat(64)),
                listOf(QuranPackFile(QuranPackLayout.tafsirAsset(id), 10, "b".repeat(64))))
        })
        val fetch = CopyOnWriteArrayList<String>()
        val cancel = CopyOnWriteArrayList<String>()
        val pending = TaskCompletionSource<AssetPackStates>().task
        val manager = Proxy.newProxyInstance(AssetPackManager::class.java.classLoader, arrayOf(AssetPackManager::class.java)) { _, method, args ->
            @Suppress("UNCHECKED_CAST")
            when (method.name) {
                "registerListener", "unregisterListener", "getPackLocation" -> null
                "getPackLocations" -> emptyMap<String, Any>()
                "getPackStates" -> pending
                "fetch" -> { fetch.addAll(args[0] as List<String>); pending }
                "cancel" -> { cancel.addAll(args[0] as List<String>); null }
                else -> error("Unexpected Play SDK method: ${method.name}")
            }
        } as AssetPackManager
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val source = PlayQuranPackSource(layout, manager, scope, onUnavailable = {})
            source.fetch(layout.tafsirPacks, allowMetered = true)
            assertEquals(layout.tafsirPacks.map { it.name }.sorted(), fetch.sorted())
            source.cancel(listOf(requireNotNull(layout.tafsir("gharib"))))
            assertEquals(listOf("quran_tafsir_gharib"), cancel)
        } finally {
            scope.cancel()
        }
    }
}
