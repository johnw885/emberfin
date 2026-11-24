package com.johnw.emberfin

import com.johnw.emberfin.browse.PreviewPlayer
import com.johnw.emberfin.data.AccountStore
import com.johnw.emberfin.data.ApiHealth
import com.johnw.emberfin.data.AuthRepository
import com.johnw.emberfin.data.JellyfinClientFactory
import com.johnw.emberfin.data.LibraryRepository
import com.johnw.emberfin.data.RowCache
import com.johnw.emberfin.data.ServerRepository
import com.johnw.emberfin.data.SessionManager
import com.johnw.emberfin.data.SessionStore
import com.johnw.emberfin.data.db.EmberfinDatabase
import com.johnw.emberfin.data.db.RoomRowCache
import com.johnw.emberfin.details.DetailsViewModel
import com.johnw.emberfin.details.PersonViewModel
import com.johnw.emberfin.home.HomeViewModel
import com.johnw.emberfin.library.GridKind
import com.johnw.emberfin.library.GridViewModel
import com.johnw.emberfin.library.LibraryTabViewModel
import com.johnw.emberfin.profiles.AddProfileViewModel
import com.johnw.emberfin.profiles.ProfilesViewModel
import com.johnw.emberfin.search.SearchViewModel
import com.johnw.emberfin.settings.SettingsStore
import com.johnw.emberfin.settings.SettingsViewModel
import com.johnw.emberfin.shell.MainViewModel
import com.johnw.emberfin.shell.Tab
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin wiring. Singletons for the data layer; ViewModels for screens behind sign-in take the
 * active session at creation (switching profile pops back to the picker, so they're recreated).
 */
fun appModule(app: EmberfinApp) = module {
    single { app.appScope }
    single { ApiHealth() }
    single { SessionStore(get()) }
    single { AccountStore(get()) }
    single { SettingsStore(get(), get()) }
    single { JellyfinClientFactory(get(), get(), BuildConfig.VERSION_NAME, get()) }
    single { ServerRepository(get()) }
    single { AuthRepository(get()) }
    single { SessionManager(get(), get(), get(), get(), get()) }
    single { LibraryRepository() }
    single { EmberfinDatabase.create(get()) }
    single<RowCache> { RoomRowCache(get<EmberfinDatabase>().screenCache()) }
    single { PreviewPlayer(get()) }

    viewModel { ProfilesViewModel(get(), get()) }
    viewModel { AddProfileViewModel(get(), get(), get()) }
    viewModel { MainViewModel(get(), get<SessionManager>().require(), get()) }
    viewModel { HomeViewModel(get(), get<SessionManager>().require(), get(), get()) }
    viewModel { (tab: Tab) -> LibraryTabViewModel(get(), get<SessionManager>().require(), get(), tab) }
    viewModel { (kind: GridKind) -> GridViewModel(get(), get<SessionManager>().require(), kind) }
    viewModel { (itemId: String) -> DetailsViewModel(get(), get<SessionManager>().require(), itemId) }
    viewModel { (personId: String) -> PersonViewModel(get(), get<SessionManager>().require(), personId) }
    viewModel { SearchViewModel(get(), get<SessionManager>().require()) }
    viewModel { SettingsViewModel(get(), get(), get<SessionManager>().require()) }
}
