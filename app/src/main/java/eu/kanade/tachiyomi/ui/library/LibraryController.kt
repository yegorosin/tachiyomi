package eu.kanade.tachiyomi.ui.library

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.appcompat.view.ActionMode
import androidx.core.view.isVisible
import com.bluelinelabs.conductor.ControllerChangeHandler
import com.bluelinelabs.conductor.ControllerChangeType
import com.fredporciuncula.flow.preferences.Preference
import com.google.android.material.tabs.TabLayout
import com.jakewharton.rxrelay.BehaviorRelay
import com.jakewharton.rxrelay.PublishRelay
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.library.LibraryUpdateService
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.databinding.LibraryControllerBinding
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.ui.base.controller.RootController
import eu.kanade.tachiyomi.ui.base.controller.SearchableNucleusController
import eu.kanade.tachiyomi.ui.base.controller.TabbedController
import eu.kanade.tachiyomi.ui.base.controller.pushController
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchController
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.manga.MangaController
import eu.kanade.tachiyomi.util.preference.asImmediateFlow
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.widget.ActionModeWithToolbar
import eu.kanade.tachiyomi.widget.EmptyView
import eu.kanade.tachiyomi.widget.materialdialogs.QuadStateTextView
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import reactivecircus.flowbinding.android.view.clicks
import reactivecircus.flowbinding.viewpager.pageSelections
import rx.Observable
import rx.Subscription
import rx.android.schedulers.AndroidSchedulers
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class LibraryController(
    bundle: Bundle? = null,
    private val preferences: PreferencesHelper = Injekt.get(),
) : SearchableNucleusController<LibraryControllerBinding, LibraryPresenter>(bundle),
    RootController,
    TabbedController,
    ActionModeWithToolbar.Callback,
    ChangeMangaCategoriesDialog.Listener,
    DeleteLibraryMangasDialog.Listener {

    /**
     * Position of the active category.
     */
    private var activeCategory: Int = preferences.lastUsedCategory().get()

    /**
     * Action mode for selections.
     */
    private var actionMode: ActionModeWithToolbar? = null

    /**
     * Currently selected mangas.
     */
    val selectedMangas = mutableSetOf<Manga>()

    /**
     * Relay to notify the UI of selection updates.
     */
    val selectionRelay: PublishRelay<LibrarySelectionEvent> = PublishRelay.create()

    /**
     * Relay to notify search query changes.
     */
    val searchRelay: BehaviorRelay<String> = BehaviorRelay.create()

    /**
     * Relay to notify the library's viewpager for updates.
     */
    val libraryMangaRelay: BehaviorRelay<LibraryMangaEvent> = BehaviorRelay.create()

    /**
     * Relay to notify the library's viewpager to select all manga
     */
    val selectAllRelay: PublishRelay<Int> = PublishRelay.create()

    /**
     * Relay to notify the library's viewpager to select the inverse
     */
    val selectInverseRelay: PublishRelay<Int> = PublishRelay.create()

    /**
     * Number of manga per row in grid mode.
     */
    var mangaPerRow = 0
        private set

    /**
     * Adapter of the view pager.
     */
    private var adapter: LibraryAdapter? = null

    /**
     * Sheet containing filter/sort/display items.
     */
    private var settingsSheet: LibrarySettingsSheet? = null

    private var tabsVisibilityRelay: BehaviorRelay<Boolean> = BehaviorRelay.create(false)

    private var mangaCountVisibilityRelay: BehaviorRelay<Boolean> = BehaviorRelay.create(false)

    private var tabsVisibilitySubscription: Subscription? = null

    private var mangaCountVisibilitySubscription: Subscription? = null

    init {
        setHasOptionsMenu(true)
        retainViewMode = RetainViewMode.RETAIN_DETACH
    }

    private var currentTitle: String? = null
        set(value) {
            if (field != value) {
                field = value
                setTitle()
            }
        }

    override fun getTitle(): String? {
        return currentTitle ?: resources?.getString(R.string.label_library)
    }

    private fun updateTitle() {
        val showCategoryTabs = preferences.categoryTabs().get()
        val currentCategory = adapter?.categories?.getOrNull(binding.libraryPager.currentItem)

        var title = if (showCategoryTabs) {
            resources?.getString(R.string.label_library)
        } else {
            currentCategory?.name
        }

        if (preferences.categoryNumberOfItems().get() && libraryMangaRelay.hasValue()) {
            libraryMangaRelay.value.mangas.let { mangaMap ->
                if (!showCategoryTabs || adapter?.categories?.size == 1) {
                    title += " (${mangaMap[currentCategory?.id]?.size ?: 0})"
                }
            }
        }

        currentTitle = title
    }

    override fun createPresenter(): LibraryPresenter {
        return LibraryPresenter()
    }

    override fun createBinding(inflater: LayoutInflater) = LibraryControllerBinding.inflate(inflater)

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)

        adapter = LibraryAdapter(this)
        binding.libraryPager.adapter = adapter
        binding.libraryPager.pageSelections()
            .drop(1)
            .onEach {
                preferences.lastUsedCategory().set(it)
                activeCategory = it
                updateTitle()
            }
            .launchIn(viewScope)

        getColumnsPreferenceForCurrentOrientation().asImmediateFlow { mangaPerRow = it }
            .drop(1)
            // Set again the adapter to recalculate the covers height
            .onEach { reattachAdapter() }
            .launchIn(viewScope)

        if (selectedMangas.isNotEmpty()) {
            createActionModeIfNeeded()
        }

        settingsSheet = LibrarySettingsSheet(router) { group ->
            when (group) {
                is LibrarySettingsSheet.Filter.FilterGroup -> onFilterChanged()
                is LibrarySettingsSheet.Sort.SortGroup -> onSortChanged()
                is LibrarySettingsSheet.Display.DisplayGroup -> {
                    val delay = if (preferences.categorizedDisplaySettings().get()) 125L else 0L

                    Observable.timer(delay, TimeUnit.MILLISECONDS, AndroidSchedulers.mainThread())
                        .subscribe {
                            reattachAdapter()
                        }
                }
                is LibrarySettingsSheet.Display.BadgeGroup -> onBadgeSettingChanged()
                is LibrarySettingsSheet.Display.TabsGroup -> onTabsSettingsChanged()
            }
        }

        binding.btnGlobalSearch.clicks()
            .onEach {
                router.pushController(GlobalSearchController(presenter.query))
            }
            .launchIn(viewScope)
    }

    override fun onChangeStarted(handler: ControllerChangeHandler, type: ControllerChangeType) {
        super.onChangeStarted(handler, type)
        if (type.isEnter) {
            (activity as? MainActivity)?.binding?.tabs?.setupWithViewPager(binding.libraryPager)
            presenter.subscribeLibrary()
        }
    }

    override fun onDestroyView(view: View) {
        destroyActionModeIfNeeded()
        adapter?.onDestroy()
        adapter = null
        settingsSheet = null
        tabsVisibilitySubscription?.unsubscribe()
        tabsVisibilitySubscription = null
        super.onDestroyView(view)
    }

    override fun configureTabs(tabs: TabLayout): Boolean {
        with(tabs) {
            isVisible = false
            tabGravity = TabLayout.GRAVITY_START
            tabMode = TabLayout.MODE_SCROLLABLE
        }
        tabsVisibilitySubscription?.unsubscribe()
        tabsVisibilitySubscription = tabsVisibilityRelay.subscribe { visible ->
            tabs.isVisible = visible
        }
        mangaCountVisibilitySubscription?.unsubscribe()
        mangaCountVisibilitySubscription = mangaCountVisibilityRelay.subscribe {
            adapter?.notifyDataSetChanged()
        }

        return false
    }

    override fun cleanupTabs(tabs: TabLayout) {
        tabsVisibilitySubscription?.unsubscribe()
        tabsVisibilitySubscription = null
    }

    fun showSettingsSheet() {
        if (adapter?.categories?.isNotEmpty() == true) {
            adapter?.categories?.get(binding.libraryPager.currentItem)?.let { category ->
                settingsSheet?.show(category)
            }
        } else {
            settingsSheet?.show()
        }
    }

    fun onNextLibraryUpdate(categories: List<Category>, mangaMap: Map<Int, List<LibraryItem>>) {
        val view = view ?: return
        val adapter = adapter ?: return

        // Show empty view if needed
        if (mangaMap.isNotEmpty()) {
            binding.emptyView.hide()
        } else {
            binding.emptyView.show(
                R.string.information_empty_library,
                listOf(
                    EmptyView.Action(R.string.getting_started_guide, R.drawable.ic_help_24dp) {
                        activity?.openInBrowser("https://tachiyomi.org/help/guides/getting-started")
                    },
                ),
            )
            (activity as? MainActivity)?.ready = true
        }

        // Get the current active category.
        val activeCat = if (adapter.categories.isNotEmpty()) {
            binding.libraryPager.currentItem
        } else {
            activeCategory
        }

        // Set the categories
        adapter.updateCategories(categories.map { it to (mangaMap[it.id]?.size ?: 0) })

        // Restore active category.
        binding.libraryPager.setCurrentItem(activeCat, false)

        // Trigger display of tabs
        onTabsSettingsChanged(firstLaunch = true)

        // Delay the scroll position to allow the view to be properly measured.
        view.post {
            if (isAttached) {
                (activity as? MainActivity)?.binding?.tabs?.setScrollPosition(binding.libraryPager.currentItem, 0f, true)
            }
        }

        // Send the manga map to child fragments after the adapter is updated.
        libraryMangaRelay.call(LibraryMangaEvent(mangaMap))

        // Finally update the title
        updateTitle()
    }

    /**
     * Returns a preference for the number of manga per row based on the current orientation.
     *
     * @return the preference.
     */
    private fun getColumnsPreferenceForCurrentOrientation(): Preference<Int> {
        return if (resources?.configuration?.orientation == Configuration.ORIENTATION_PORTRAIT) {
            preferences.portraitColumns()
        } else {
            preferences.landscapeColumns()
        }
    }

    private fun onFilterChanged() {
        presenter.requestFilterUpdate()
        activity?.invalidateOptionsMenu()
    }

    private fun onBadgeSettingChanged() {
        presenter.requestBadgesUpdate()
    }

    private fun onTabsSettingsChanged(firstLaunch: Boolean = false) {
        if (!firstLaunch) {
            mangaCountVisibilityRelay.call(preferences.categoryNumberOfItems().get())
        }
        tabsVisibilityRelay.call(preferences.categoryTabs().get() && adapter?.categories?.size ?: 0 > 1)
        updateTitle()
    }

    /**
     * Called when the sorting mode is changed.
     */
    private fun onSortChanged() {
        presenter.requestSortUpdate()
    }

    /**
     * Reattaches the adapter to the view pager to recreate fragments
     */
    private fun reattachAdapter() {
        val adapter = adapter ?: return

        val position = binding.libraryPager.currentItem

        adapter.recycle = false
        binding.libraryPager.adapter = adapter
        binding.libraryPager.currentItem = position
        adapter.recycle = true
    }

    /**
     * Creates the action mode if it's not created already.
     */
    fun createActionModeIfNeeded() {
        val activity = activity
        if (actionMode == null && activity is MainActivity) {
            actionMode = activity.startActionModeAndToolbar(this)
            activity.showBottomNav(false)
        }
    }

    /**
     * Destroys the action mode.
     */
    private fun destroyActionModeIfNeeded() {
        actionMode?.finish()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        createOptionsMenu(menu, inflater, R.menu.library, R.id.action_search)
        // Mutate the filter icon because it needs to be tinted and the resource is shared.
        menu.findItem(R.id.action_filter).icon.mutate()
    }

    fun search(query: String) {
        presenter.query = query
    }

    private fun performSearch() {
        searchRelay.call(presenter.query)
        if (presenter.query.isNotEmpty()) {
            binding.btnGlobalSearch.isVisible = true
            binding.btnGlobalSearch.text =
                resources?.getString(R.string.action_global_search_query, presenter.query)
        } else {
            binding.btnGlobalSearch.isVisible = false
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        val settingsSheet = settingsSheet ?: return

        val filterItem = menu.findItem(R.id.action_filter)

        // Tint icon if there's a filter active
        if (settingsSheet.filters.hasActiveFilters()) {
            val filterColor = activity!!.getResourceColor(R.attr.colorFilterActive)
            filterItem.icon.setTint(filterColor)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_search -> expandActionViewFromInteraction = true
            R.id.action_filter -> showSettingsSheet()
            R.id.action_update_library -> {
                activity?.let {
                    if (LibraryUpdateService.start(it)) {
                        it.toast(R.string.updating_library)
                    }
                }
            }
        }

        return super.onOptionsItemSelected(item)
    }

    /**
     * Invalidates the action mode, forcing it to refresh its content.
     */
    fun invalidateActionMode() {
        actionMode?.invalidate()
    }

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        mode.menuInflater.inflate(R.menu.generic_selection, menu)
        return true
    }

    override fun onCreateActionToolbar(menuInflater: MenuInflater, menu: Menu) {
        menuInflater.inflate(R.menu.library_selection, menu)
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        val count = selectedMangas.size
        if (count == 0) {
            // Destroy action mode if there are no items selected.
            destroyActionModeIfNeeded()
        } else {
            mode.title = count.toString()
        }
        return true
    }

    override fun onPrepareActionToolbar(toolbar: ActionModeWithToolbar, menu: Menu) {
        if (selectedMangas.isEmpty()) return
        toolbar.findToolbarItem(R.id.action_download_unread)?.isVisible =
            selectedMangas.any { it.source != LocalSource.ID }
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_move_to_category -> showChangeMangaCategoriesDialog()
            R.id.action_download_unread -> downloadUnreadChapters()
            R.id.action_mark_as_read -> markReadStatus(true)
            R.id.action_mark_as_unread -> markReadStatus(false)
            R.id.action_delete -> showDeleteMangaDialog()
            R.id.action_select_all -> selectAllCategoryManga()
            R.id.action_select_inverse -> selectInverseCategoryManga()
            else -> return false
        }
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        // Clear all the manga selections and notify child views.
        selectedMangas.clear()
        selectionRelay.call(LibrarySelectionEvent.Cleared())

        (activity as? MainActivity)?.showBottomNav(true)

        actionMode = null
    }

    fun openManga(manga: Manga) {
        // Notify the presenter a manga is being opened.
        presenter.onOpenManga()

        router.pushController(MangaController(manga))
    }

    /**
     * Sets the selection for a given manga.
     *
     * @param manga the manga whose selection has changed.
     * @param selected whether it's now selected or not.
     */
    fun setSelection(manga: Manga, selected: Boolean) {
        if (selected) {
            if (selectedMangas.add(manga)) {
                selectionRelay.call(LibrarySelectionEvent.Selected(manga))
            }
        } else {
            if (selectedMangas.remove(manga)) {
                selectionRelay.call(LibrarySelectionEvent.Unselected(manga))
            }
        }
    }

    /**
     * Toggles the current selection state for a given manga.
     *
     * @param manga the manga whose selection to change.
     */
    fun toggleSelection(manga: Manga) {
        if (selectedMangas.add(manga)) {
            selectionRelay.call(LibrarySelectionEvent.Selected(manga))
        } else if (selectedMangas.remove(manga)) {
            selectionRelay.call(LibrarySelectionEvent.Unselected(manga))
        }
    }

    /**
     * Clear all of the manga currently selected, and
     * invalidate the action mode to revert the top toolbar
     */
    fun clearSelection() {
        selectedMangas.clear()
        selectionRelay.call(LibrarySelectionEvent.Cleared())
        invalidateActionMode()
    }

    /**
     * Move the selected manga to a list of categories.
     */
    private fun showChangeMangaCategoriesDialog() {
        // Create a copy of selected manga
        val mangas = selectedMangas.toList()

        // Hide the default category because it has a different behavior than the ones from db.
        val categories = presenter.categories.filter { it.id != 0 }

        // Get indexes of the common categories to preselect.
        val common = presenter.getCommonCategories(mangas)
        // Get indexes of the mix categories to preselect.
        val mix = presenter.getMixCategories(mangas)
        val preselected = categories.map {
            when (it) {
                in common -> QuadStateTextView.State.CHECKED.ordinal
                in mix -> QuadStateTextView.State.INDETERMINATE.ordinal
                else -> QuadStateTextView.State.UNCHECKED.ordinal
            }
        }.toTypedArray()
        ChangeMangaCategoriesDialog(this, mangas, categories, preselected)
            .showDialog(router)
    }

    private fun downloadUnreadChapters() {
        val mangas = selectedMangas.toList()
        presenter.downloadUnreadChapters(mangas)
        destroyActionModeIfNeeded()
    }

    private fun markReadStatus(read: Boolean) {
        val mangas = selectedMangas.toList()
        presenter.markReadStatus(mangas, read)
        destroyActionModeIfNeeded()
    }

    private fun showDeleteMangaDialog() {
        DeleteLibraryMangasDialog(this, selectedMangas.toList()).showDialog(router)
    }

    override fun updateCategoriesForMangas(mangas: List<Manga>, addCategories: List<Category>, removeCategories: List<Category>) {
        presenter.updateMangasToCategories(mangas, addCategories, removeCategories)
        destroyActionModeIfNeeded()
    }

    override fun deleteMangas(mangas: List<Manga>, deleteFromLibrary: Boolean, deleteChapters: Boolean) {
        presenter.removeMangas(mangas, deleteFromLibrary, deleteChapters)
        destroyActionModeIfNeeded()
    }

    private fun selectAllCategoryManga() {
        adapter?.categories?.getOrNull(binding.libraryPager.currentItem)?.id?.let {
            selectAllRelay.call(it)
        }
    }

    private fun selectInverseCategoryManga() {
        adapter?.categories?.getOrNull(binding.libraryPager.currentItem)?.id?.let {
            selectInverseRelay.call(it)
        }
    }

    override fun onSearchViewQueryTextChange(newText: String?) {
        // Ignore events if this controller isn't at the top to avoid query being reset
        if (router.backstack.lastOrNull()?.controller == this) {
            presenter.query = newText ?: ""
            performSearch()
        }
    }
}
