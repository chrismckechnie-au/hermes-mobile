package au.com.chrismckechnie.hermesmobile

/**
 * The independently loadable states used by the app's remote collections.
 *
 * [Error.cached] keeps previously rendered content available while making the
 * failed refresh explicit. [Unsupported] is reserved for host capabilities or
 * endpoints that genuinely are not available; it is not an empty result.
 */
sealed interface ResourceState<out T> {
    data object Loading : ResourceState<Nothing>

    data class Data<T>(
        val value: T,
        val refreshing: Boolean = false,
    ) : ResourceState<T>

    data class Empty(
        val refreshing: Boolean = false,
    ) : ResourceState<Nothing>

    data class Error<T>(
        val message: String,
        val cached: T? = null,
    ) : ResourceState<T>

    data object Unsupported : ResourceState<Nothing>

    val isRefreshing: Boolean
        get() = when (this) {
            is Data -> refreshing
            is Empty -> refreshing
            is Error, Loading, Unsupported -> false
        }

    fun valueOrNull(): T? = when (this) {
        is Data -> value
        is Error -> cached
        is Empty, Loading, Unsupported -> null
    }
}

internal fun <T> List<T>.asResourceState(refreshing: Boolean = false): ResourceState<List<T>> =
    if (isEmpty()) ResourceState.Empty(refreshing) else ResourceState.Data(this, refreshing)

internal fun <T> ResourceState<List<T>>.itemsOrEmpty(): List<T> = valueOrNull().orEmpty()

/** A successful response, including a genuine empty response; null for all non-success states. */
internal fun <T> ResourceState<List<T>>.loadedItemsOrNull(): List<T>? = when (this) {
    is ResourceState.Data -> value
    is ResourceState.Empty -> emptyList()
    is ResourceState.Error, ResourceState.Loading, ResourceState.Unsupported -> null
}

/** Apply a local collection mutation without discarding a visible refresh error. */
internal fun <T> ResourceState<List<T>>.withItems(items: List<T>): ResourceState<List<T>> = when (this) {
    is ResourceState.Error -> copy(cached = items)
    ResourceState.Unsupported -> this
    else -> items.asResourceState(refreshing = isRefreshing)
}

internal fun <T> ResourceState<List<T>>.beginRefresh(): ResourceState<List<T>> = when (this) {
    is ResourceState.Data -> copy(refreshing = true)
    is ResourceState.Empty -> copy(refreshing = true)
    is ResourceState.Error -> cached?.asResourceState(refreshing = true) ?: ResourceState.Loading
    ResourceState.Loading -> this
    ResourceState.Unsupported -> this
}

internal fun <T> ResourceState<List<T>>.refreshError(message: String): ResourceState<List<T>> =
    ResourceState.Error(message = message, cached = valueOrNull() ?: emptyList())
