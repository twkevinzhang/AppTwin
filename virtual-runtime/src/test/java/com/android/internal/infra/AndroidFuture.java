package com.android.internal.infra;

import java.util.concurrent.CompletableFuture;

/** JVM fixture for Android 12's hidden Parcelable CompletableFuture contract. */
public class AndroidFuture<T> extends CompletableFuture<T> {
    public AndroidFuture() {}
}
