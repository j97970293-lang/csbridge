package aniyomi.csbridge.source

import kotlinx.coroutines.runBlocking
import rx.Observable
import java.util.concurrent.Callable

/**
 * Bridges the coroutine based source API to the legacy RxJava one.
 *
 * Some Aniyomi forks - AniZen notably - still declare
 * `AnimeCatalogueSource.fetchPopularAnime / fetchSearchAnime /
 * fetchLatestUpdates` as ABSTRACT methods (they kept the RxJava API as the
 * primary one and gave the suspend functions a default implementation).
 *
 * A class that does not implement every abstract method of its interfaces is
 * turned into an abstract class by ART; `newInstance()` then throws and the
 * host silently drops the *whole* extension (AnimeExtensionLoader returns
 * LoadResult.Error). That is why these entry points are always implemented.
 *
 * The evaluation is deferred to subscription time (fromCallable), so no network
 * call happens on the calling thread.
 */
internal fun <T> rxObservable(block: suspend () -> T): Observable<T> =
    Observable.fromCallable(Callable { runBlocking { block() } })
