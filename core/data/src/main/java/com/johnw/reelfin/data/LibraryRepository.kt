package com.johnw.reelfin.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.genreApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.showApi
import org.jellyfin.sdk.api.client.extensions.userDataApi
import org.jellyfin.sdk.api.client.extensions.userViewApi
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.request.GetLatestMediaRequest
import org.jellyfin.sdk.model.api.request.GetNextUpRequest
import org.jellyfin.sdk.model.api.request.GetResumeItemsRequest

data class HomeRow(val title: String, val items: List<BaseItemDto>)

/** Sort choices for the library grids. */
enum class LibrarySort(val label: String, val sortBy: ItemSortBy, val order: SortOrder) {
    Name("A–Z", ItemSortBy.SORT_NAME, SortOrder.ASCENDING),
    Added("Recently added", ItemSortBy.DATE_CREATED, SortOrder.DESCENDING),
    Released("Release date", ItemSortBy.PREMIERE_DATE, SortOrder.DESCENDING),
    Rating("Rating", ItemSortBy.COMMUNITY_RATING, SortOrder.DESCENDING),
}

data class LibraryQuery(
    val parentId: UUID? = null,
    val kinds: List<BaseItemKind>,
    val sort: LibrarySort = LibrarySort.Name,
    val unwatchedOnly: Boolean = false,
    val genreId: UUID? = null,
)

data class Page(val items: List<BaseItemDto>, val total: Int)

/** Every library read the browse screens make. All calls run on IO. */
class LibraryRepository {
    private val imageTypes = listOf(ImageType.PRIMARY, ImageType.THUMB, ImageType.BACKDROP, ImageType.LOGO)
    private val listFields = listOf(ItemFields.OVERVIEW, ItemFields.GENRES, ItemFields.PRIMARY_IMAGE_ASPECT_RATIO)

    suspend fun views(s: ActiveSession): List<BaseItemDto> = io {
        s.api.userViewApi.getUserViews(userId = s.userId).content.items
    }

    suspend fun resume(s: ActiveSession, limit: Int = 24): List<BaseItemDto> = io {
        s.api.libraryApi.getResumeItems(
            GetResumeItemsRequest(
                userId = s.userId, limit = limit, mediaTypes = listOf(MediaType.VIDEO), fields = listFields,
                enableUserData = true, enableImageTypes = imageTypes, imageTypeLimit = 1,
            ),
        ).content.items
    }

    suspend fun nextUp(s: ActiveSession, limit: Int = 24, seriesId: UUID? = null): List<BaseItemDto> = io {
        s.api.showApi.getNextUp(
            GetNextUpRequest(
                userId = s.userId, limit = limit, seriesId = seriesId, fields = listFields, enableUserData = true,
                enableImageTypes = imageTypes, imageTypeLimit = 1, enableResumable = false, enableRewatching = false,
            ),
        ).content.items
    }

    /** Recently added in one library. Episodes are grouped into their series, like Jellyfin web. */
    suspend fun latest(s: ActiveSession, parentId: UUID, limit: Int = 24): List<BaseItemDto> = io {
        s.api.libraryApi.getLatestMedia(
            GetLatestMediaRequest(
                userId = s.userId, parentId = parentId, limit = limit, fields = listFields, enableUserData = true,
                enableImageTypes = imageTypes, imageTypeLimit = 1, groupItems = true,
            ),
        ).content
    }

    /** "My List" = the user's favourites (movies, series, episodes). */
    suspend fun favorites(s: ActiveSession, limit: Int = 40): List<BaseItemDto> = items(
        s, kinds = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.EPISODE, BaseItemKind.VIDEO),
        filters = listOf(ItemFilter.IS_FAVORITE), sortBy = listOf(ItemSortBy.DATE_CREATED), order = SortOrder.DESCENDING, limit = limit,
    ).items

    /** Genres that have items of [kinds], most populated first. */
    suspend fun genres(s: ActiveSession, kinds: List<BaseItemKind>, parentId: UUID? = null): List<BaseItemDto> = io {
        s.api.genreApi.getGenres(
            userId = s.userId, includeItemTypes = kinds, parentId = parentId, fields = listOf(ItemFields.ITEM_COUNTS),
            enableImages = false, enableTotalRecordCount = false,
        ).content.items
    }

    suspend fun byGenre(s: ActiveSession, genreId: UUID, kinds: List<BaseItemKind>, parentId: UUID? = null, limit: Int = 24): List<BaseItemDto> =
        items(s, kinds = kinds, parentId = parentId, genreIds = listOf(genreId), sortBy = listOf(ItemSortBy.RANDOM), limit = limit).items

    /** Billboard candidates: unwatched titles that have both a backdrop and a clear logo. */
    suspend fun featured(s: ActiveSession, kinds: List<BaseItemKind>, parentId: UUID? = null, limit: Int = 8): List<BaseItemDto> {
        val withArt = items(
            s, kinds = kinds, parentId = parentId, filters = listOf(ItemFilter.IS_UNPLAYED),
            imageTypesFilter = listOf(ImageType.BACKDROP, ImageType.LOGO), sortBy = listOf(ItemSortBy.RANDOM), limit = limit,
        ).items
        if (withArt.isNotEmpty()) return withArt
        // Everything watched or no logos: any title with a backdrop.
        return items(
            s, kinds = kinds, parentId = parentId, imageTypesFilter = listOf(ImageType.BACKDROP),
            sortBy = listOf(ItemSortBy.RANDOM), limit = limit,
        ).items
    }

    /** One page of a library grid. */
    suspend fun page(s: ActiveSession, q: LibraryQuery, start: Int, limit: Int): Page {
        val r = items(
            s, kinds = q.kinds, parentId = q.parentId, genreIds = q.genreId?.let { listOf(it) },
            filters = if (q.unwatchedOnly) listOf(ItemFilter.IS_UNPLAYED) else null,
            sortBy = listOf(q.sort.sortBy, ItemSortBy.SORT_NAME), order = q.sort.order,
            start = start, limit = limit, total = true,
        )
        return r
    }

    suspend fun seasons(s: ActiveSession, seriesId: UUID): List<BaseItemDto> = io {
        s.api.showApi.getSeasons(seriesId = seriesId, userId = s.userId, enableUserData = true, enableImageTypes = imageTypes, imageTypeLimit = 1)
            .content.items
    }

    suspend fun episodes(s: ActiveSession, seriesId: UUID, seasonId: UUID): List<BaseItemDto> = io {
        s.api.showApi.getEpisodes(
            seriesId = seriesId, userId = s.userId, seasonId = seasonId, fields = listOf(ItemFields.OVERVIEW),
            enableUserData = true, enableImages = true, enableImageTypes = imageTypes, imageTypeLimit = 1,
        ).content.items
    }

    suspend fun similar(s: ActiveSession, itemId: UUID, limit: Int = 16): List<BaseItemDto> = io {
        s.api.libraryApi.getSimilarItems(itemId = itemId, userId = s.userId, limit = limit, fields = listFields).content.items
    }

    /** A person's titles in this library, newest first. */
    suspend fun filmography(s: ActiveSession, personId: UUID): List<BaseItemDto> = items(
        s, kinds = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES), personIds = listOf(personId),
        sortBy = listOf(ItemSortBy.PREMIERE_DATE), order = SortOrder.DESCENDING, limit = 100,
    ).items

    suspend fun item(s: ActiveSession, itemId: UUID): BaseItemDto = item(s.api, s.userId, itemId)

    /** Local trailers (Session S downloads one per title). */
    suspend fun localTrailers(s: ActiveSession, itemId: UUID): List<BaseItemDto> = io {
        s.api.libraryApi.getLocalTrailers(itemId = itemId, userId = s.userId).content
    }

    /** Adds to or removes from My List; returns the new state. */
    suspend fun setFavorite(s: ActiveSession, itemId: UUID, favorite: Boolean): Boolean = io {
        val r = if (favorite) s.api.userDataApi.markFavoriteItem(itemId = itemId, userId = s.userId)
        else s.api.userDataApi.unmarkFavoriteItem(itemId = itemId, userId = s.userId)
        r.content.isFavorite
    }

    private suspend fun items(
        s: ActiveSession,
        kinds: List<BaseItemKind>,
        parentId: UUID? = null,
        filters: List<ItemFilter>? = null,
        genreIds: List<UUID>? = null,
        personIds: List<UUID>? = null,
        imageTypesFilter: List<ImageType>? = null,
        sortBy: List<ItemSortBy>? = null,
        order: SortOrder = SortOrder.ASCENDING,
        start: Int? = null,
        limit: Int? = null,
        total: Boolean = false,
    ): Page = io {
        val r = s.api.libraryApi.getItems(
            GetItemsRequest(
                userId = s.userId, parentId = parentId, includeItemTypes = kinds, recursive = true,
                filters = filters, genreIds = genreIds, personIds = personIds, imageTypes = imageTypesFilter,
                sortBy = sortBy, sortOrder = sortBy?.let { List(it.size) { order } },
                startIndex = start, limit = limit, fields = listFields, enableUserData = true,
                enableImageTypes = imageTypes, imageTypeLimit = 1, enableTotalRecordCount = total,
            ),
        ).content
        Page(r.items, r.totalRecordCount)
    }

    // --- Used by the player (Session 2) -------------------------------------------------------

    /** One item with everything the player and details page need (user data, media sources, people). */
    suspend fun item(api: ApiClient, userId: UUID, itemId: UUID): BaseItemDto = io {
        api.userLibraryItem(userId, itemId)
    }

    /** The episode after [episode] in its series (across seasons), or null for the last one or a non-episode. */
    suspend fun nextEpisode(api: ApiClient, userId: UUID, episode: BaseItemDto): BaseItemDto? = io {
        if (episode.type != BaseItemKind.EPISODE) return@io null
        val seriesId = episode.seriesId ?: return@io null
        val items = api.showApi.getEpisodes(
            seriesId = seriesId,
            userId = userId,
            startItemId = episode.id,
            limit = 2,
            enableUserData = true,
            enableImages = true,
            enableImageTypes = imageTypes,
            imageTypeLimit = 1,
        ).content.items
        items.dropWhile { it.id == episode.id }.firstOrNull()
    }

    private suspend fun ApiClient.userLibraryItem(userId: UUID, itemId: UUID): BaseItemDto =
        libraryApi.getItem(itemId = itemId, userId = userId).content

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        val MovieKinds = listOf(BaseItemKind.MOVIE)
        val ShowKinds = listOf(BaseItemKind.SERIES)
        val HomeVideoKinds = listOf(BaseItemKind.VIDEO, BaseItemKind.MOVIE)

        fun isMovies(view: BaseItemDto) = view.collectionType == CollectionType.MOVIES
        fun isShows(view: BaseItemDto) = view.collectionType == CollectionType.TVSHOWS
        fun isHomeVideos(view: BaseItemDto) = view.collectionType == CollectionType.HOMEVIDEOS
    }
}
