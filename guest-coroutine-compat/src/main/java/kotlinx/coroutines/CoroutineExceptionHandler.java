package kotlinx.coroutines;

import X.$6S;
import X.$LG;

/** Compile-only shape of Facebook Lite's transformed coroutine handler ABI. */
public interface CoroutineExceptionHandler {
    $LG Key = new $LG();

    void handleException($6S context, Throwable exception);
}
