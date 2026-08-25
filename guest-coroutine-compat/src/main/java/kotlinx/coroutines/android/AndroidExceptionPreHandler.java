package kotlinx.coroutines.android;

import X.$6S;
import X.$Kn;
import kotlinx.coroutines.CoroutineExceptionHandler;

/**
 * API 28+ replacement for the provider still named by Facebook Lite's service descriptor.
 *
 * <p>The upstream pre-handler only forwarded failures on API 26 and 27. AppTwin injects this
 * class exclusively on API 28+, where the correct behavior is therefore a no-op.</p>
 */
public final class AndroidExceptionPreHandler extends $Kn
        implements CoroutineExceptionHandler {

    public AndroidExceptionPreHandler() {
        super(CoroutineExceptionHandler.Key);
    }

    @Override
    public void handleException($6S context, Throwable exception) {
        // No-op on the only API levels where this compatibility class can be installed.
    }
}
