package rx;

/**
 * Compile-time stub of RxJava 1.x's Observable.
 *
 * It is ONLY used by kotlinc: the class is compiled into a separate directory
 * that is added to the classpath but never dexed into the APK, so at runtime
 * the real `rx.Observable` shipped by the host app (Aniyomi, AniZen, Mihon...)
 * is used.
 *
 * Some Aniyomi forks (AniZen, Komikku-derived source-api) still declare the
 * legacy RxJava entry points of AnimeCatalogueSource as ABSTRACT members, so
 * the bridge has to implement them to stay instantiable.
 */
public class Observable<T> {

    public static <T> Observable<T> fromCallable(java.util.concurrent.Callable<? extends T> func) {
        throw new UnsupportedOperationException("stub");
    }

    public static <T> Observable<T> just(T value) {
        throw new UnsupportedOperationException("stub");
    }

    public static <T> Observable<T> error(Throwable throwable) {
        throw new UnsupportedOperationException("stub");
    }
}
