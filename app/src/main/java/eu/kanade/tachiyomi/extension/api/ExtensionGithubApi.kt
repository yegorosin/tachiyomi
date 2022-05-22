package eu.kanade.tachiyomi.extension.api

import android.content.Context
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.extension.model.AvailableExtensionSources
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.parseAs
import eu.kanade.tachiyomi.util.lang.withIOContext
import eu.kanade.tachiyomi.util.system.logcat
import kotlinx.serialization.Serializable
import logcat.LogPriority
import uy.kohesive.injekt.injectLazy
import java.util.Date
import java.util.concurrent.TimeUnit

internal class ExtensionGithubApi {

    private val networkService: NetworkHelper by injectLazy()
    private val preferences: PreferencesHelper by injectLazy()

    private var requiresFallbackSource = false

    suspend fun findExtensions(): List<Extension.Available> {
        return withIOContext {
            val response = try {
                networkService.client
                    .newCall(GET("${REPO_URL_PREFIX}index.min.json"))
                    .await()
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "Failed to get extensions from GitHub" }
                requiresFallbackSource = true

                networkService.client
                    .newCall(GET("${FALLBACK_REPO_URL_PREFIX}index.min.json"))
                    .await()
            }

            val extensions = response
                .parseAs<List<ExtensionJsonObject>>()
                .toExtensions()

            // Sanity check - a small number of extensions probably means something broke
            // with the repo generator
            if (extensions.size < 100) {
                throw Exception()
            }

            extensions
        }
    }

    suspend fun checkForUpdates(context: Context): List<Extension.Installed>? {
        // Limit checks to once a day at most
        if (Date().time < preferences.lastExtCheck().get() + TimeUnit.DAYS.toMillis(1)) {
            return null
        }

        val extensions = findExtensions()

        preferences.lastExtCheck().set(Date().time)

        val installedExtensions = ExtensionLoader.loadExtensions(context)
            .filterIsInstance<LoadResult.Success>()
            .map { it.extension }

        val extensionsWithUpdate = mutableListOf<Extension.Installed>()
        for (installedExt in installedExtensions) {
            val pkgName = installedExt.pkgName
            val availableExt = extensions.find { it.pkgName == pkgName } ?: continue

            val hasUpdate = availableExt.versionCode > installedExt.versionCode
            if (hasUpdate) {
                extensionsWithUpdate.add(installedExt)
            }
        }

        return extensionsWithUpdate
    }

    private fun List<ExtensionJsonObject>.toExtensions(): List<Extension.Available> {
        return this
            .filter {
                val libVersion = it.version.substringBeforeLast('.').toDouble()
                libVersion >= ExtensionLoader.LIB_VERSION_MIN && libVersion <= ExtensionLoader.LIB_VERSION_MAX
            }
            .map {
                Extension.Available(
                    name = it.name.substringAfter("Tachiyomi: "),
                    pkgName = it.pkg,
                    versionName = it.version,
                    versionCode = it.code,
                    lang = it.lang,
                    isNsfw = it.nsfw == 1,
                    hasReadme = it.hasReadme == 1,
                    hasChangelog = it.hasChangelog == 1,
                    sources = it.sources?.toExtensionSources() ?: emptyList(),
                    apkName = it.apk,
                    iconUrl = "${getUrlPrefix()}icon/${it.apk.replace(".apk", ".png")}",
                )
            }
    }

    private fun List<ExtensionSourceJsonObject>.toExtensionSources(): List<AvailableExtensionSources> {
        return this.map {
            AvailableExtensionSources(
                name = it.name,
                id = it.id,
                baseUrl = it.baseUrl,
            )
        }
    }

    fun getApkUrl(extension: Extension.Available): String {
        return "${getUrlPrefix()}apk/${extension.apkName}"
    }

    private fun getUrlPrefix(): String {
        return if (requiresFallbackSource) {
            FALLBACK_REPO_URL_PREFIX
        } else {
            REPO_URL_PREFIX
        }
    }
}

private const val REPO_URL_PREFIX = "https://raw.githubusercontent.com/tachiyomiorg/tachiyomi-extensions/repo/"
private const val FALLBACK_REPO_URL_PREFIX = "https://fastly.jsdelivr.net/gh/tachiyomiorg/tachiyomi-extensions@repo/"

@Serializable
private data class ExtensionJsonObject(
    val name: String,
    val pkg: String,
    val apk: String,
    val lang: String,
    val code: Long,
    val version: String,
    val nsfw: Int,
    val hasReadme: Int = 0,
    val hasChangelog: Int = 0,
    val sources: List<ExtensionSourceJsonObject>?,
)

@Serializable
private data class ExtensionSourceJsonObject(
    val name: String,
    val id: Long,
    val baseUrl: String,
)
