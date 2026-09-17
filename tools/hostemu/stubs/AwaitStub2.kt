package tachiyomi.core.common.util.lang

import rx.Observable

suspend fun <T> Observable<T>.awaitSingle(): T = error("host stub")
