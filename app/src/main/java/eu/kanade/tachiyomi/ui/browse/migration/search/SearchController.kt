package eu.kanade.tachiyomi.ui.browse.migration.search

import android.app.Dialog
import android.os.Bundle
import androidx.core.view.isVisible
import com.bluelinelabs.conductor.Controller
import com.bluelinelabs.conductor.RouterTransaction
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.ui.base.controller.DialogController
import eu.kanade.tachiyomi.ui.base.controller.pushController
import eu.kanade.tachiyomi.ui.browse.migration.MigrationFlags
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchController
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchPresenter
import eu.kanade.tachiyomi.ui.manga.MangaController
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class SearchController(
    private var manga: Manga? = null,
) : GlobalSearchController(manga?.title) {

    constructor(mangaId: Long) : this(
        Injekt.get<DatabaseHelper>()
            .getManga(mangaId)
            .executeAsBlocking(),
    )

    private var newManga: Manga? = null

    override fun createPresenter(): GlobalSearchPresenter {
        return SearchPresenter(
            initialQuery,
            manga!!,
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putSerializable(::manga.name, manga)
        outState.putSerializable(::newManga.name, newManga)
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        manga = savedInstanceState.getSerializable(::manga.name) as? Manga
        newManga = savedInstanceState.getSerializable(::newManga.name) as? Manga
    }

    fun migrateManga(manga: Manga? = null, newManga: Manga?) {
        manga ?: return
        newManga ?: return

        (presenter as? SearchPresenter)?.migrateManga(manga, newManga, true)
    }

    fun copyManga(manga: Manga? = null, newManga: Manga?) {
        manga ?: return
        newManga ?: return

        (presenter as? SearchPresenter)?.migrateManga(manga, newManga, false)
    }

    override fun onMangaClick(manga: Manga) {
        newManga = manga
        val dialog =
            MigrationDialog(this.manga, newManga, this)
        dialog.targetController = this
        dialog.showDialog(router)
    }

    override fun onMangaLongClick(manga: Manga) {
        // Call parent's default click listener
        super.onMangaClick(manga)
    }

    fun renderIsReplacingManga(isReplacingManga: Boolean, newManga: Manga?) {
        binding.progress.isVisible = isReplacingManga
        if (!isReplacingManga) {
            router.popController(this)
            if (newManga != null) {
                val newMangaController = RouterTransaction.with(MangaController(newManga))
                if (router.backstack.lastOrNull()?.controller is MangaController) {
                    // Replace old MangaController
                    router.replaceTopController(newMangaController)
                } else {
                    // Push MangaController on top of MigrationController
                    router.pushController(newMangaController)
                }
            }
        }
    }

    class MigrationDialog(private val manga: Manga? = null, private val newManga: Manga? = null, private val callingController: Controller? = null) : DialogController() {

        private val preferences: PreferencesHelper by injectLazy()

        @Suppress("DEPRECATION")
        override fun onCreateDialog(savedViewState: Bundle?): Dialog {
            val prefValue = preferences.migrateFlags().get()
            val enabledFlagsPositions = MigrationFlags.getEnabledFlagsPositions(prefValue)
            val items = MigrationFlags.titles
                .map { resources?.getString(it) }
                .toTypedArray()
            val selected = items
                .mapIndexed { i, _ -> enabledFlagsPositions.contains(i) }
                .toBooleanArray()

            return MaterialAlertDialogBuilder(activity!!)
                .setTitle(R.string.migration_dialog_what_to_include)
                .setMultiChoiceItems(items, selected) { _, which, checked ->
                    selected[which] = checked
                }
                .setPositiveButton(R.string.migrate) { _, _ ->
                    // Save current settings for the next time
                    val selectedIndices = mutableListOf<Int>()
                    selected.forEachIndexed { i, b -> if (b) selectedIndices.add(i) }
                    val newValue = MigrationFlags.getFlagsFromPositions(selectedIndices.toTypedArray())
                    preferences.migrateFlags().set(newValue)

                    if (callingController != null) {
                        if (callingController.javaClass == SourceSearchController::class.java) {
                            router.popController(callingController)
                        }
                    }
                    (targetController as? SearchController)?.migrateManga(manga, newManga)
                }
                .setNegativeButton(R.string.copy) { _, _ ->
                    if (callingController != null) {
                        if (callingController.javaClass == SourceSearchController::class.java) {
                            router.popController(callingController)
                        }
                    }
                    (targetController as? SearchController)?.copyManga(manga, newManga)
                }
                .setNeutralButton(activity?.getString(R.string.action_show_manga)) { _, _ ->
                    dismissDialog()
                    router.pushController(MangaController(newManga))
                }
                .create()
        }
    }

    override fun onTitleClick(source: CatalogueSource) {
        presenter.preferences.lastUsedSource().set(source.id)

        router.pushController(SourceSearchController(manga, source, presenter.query))
    }
}
