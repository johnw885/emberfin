package com.johnw.reelfin

import com.johnw.reelfin.data.AccountStore
import com.johnw.reelfin.data.ApiHealth
import com.johnw.reelfin.data.AuthRepository
import com.johnw.reelfin.data.JellyfinClientFactory
import com.johnw.reelfin.data.LibraryRepository
import com.johnw.reelfin.data.RowCache
import com.johnw.reelfin.data.ServerRepository
import com.johnw.reelfin.data.SessionManager
import com.johnw.reelfin.data.SessionStore
import com.johnw.reelfin.data.db.ReelfinDatabase
import com.johnw.reelfin.data.db.RoomRowCache
import com.johnw.reelfin.details.DetailsViewModel
import com.johnw.reelfin.details.PersonViewModel
import com.johnw.reelfin.home.HomeViewModel
import com.johnw.reelfin.library.GridKind
import com.johnw.reelfin.library.GridViewModel
import com.johnw.reelfin.library.LibraryTabViewModel
import com.johnw.reelfin.profiles.AddProfileViewModel
import com.johnw.reelfin.profiles.ProfilesViewModel
import com.johnw.reelfin.search.SearchViewModel
import com.johnw.reelfin.settings.SettingsStore
import com.johnw.reelfin.settings.SettingsViewModel
import com.johnw.reelfin.shell.MainViewModel
import com.johnw.reelfin.shell.Tab
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin wiring. Singletons for the data layer; ViewModels for screens behind sign-in take the
 * active session at creation (switching profile pops back to the picker, so they're recreated).
 */
fun appModule(app: ReelfinApp) = module {
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
    single { ReelfinDatabase.create(get()) }
    single<RowCache> { RoomRowCache(get<ReelfinDatabase>().screenCache()) }

    viewModel { ProfilesViewModel(get(), get()) }
    viewModel { AddProfileViewModel(get(), get(), get()) }
    viewModel { MainViewModel(get(), get<SessionManager>().require()) }
    viewModel { HomeViewModel(get(), get<SessionManager>().require(), get()) }
    viewModel { (tab: Tab) -> LibraryTabViewModel(get(), get<SessionManager>().require(), get(), tab) }
    viewModel { (kind: GridKind) -> GridViewModel(get(), get<SessionManager>().require(), kind) }
    viewModel { (itemId: String) -> DetailsViewModel(get(), get<SessionManager>().require(), itemId) }
    viewModel { (personId: String) -> PersonViewModel(get(), get<SessionManager>().require(), personId) }
    viewModel { SearchViewModel(get(), get<SessionManager>().require()) }
    viewModel { SettingsViewModel(get(), get(), get<SessionManager>().require()) }
}
