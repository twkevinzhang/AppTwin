package com.lody.virtual.client.stub;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;

/** Read-only, grant-only access to {@link OutboundFileStore} copies for external apps. */
public final class OutboundFileProvider extends ContentProvider {
    private static final String DEFAULT_MIME = "application/octet-stream";
    private static volatile OutboundFileStore store;

    public static String authority(Context context) {
        return context.getPackageName() + ".outbound";
    }

    public static OutboundFileStore store(Context context) {
        OutboundFileStore local = store;
        if (local == null) {
            synchronized (OutboundFileProvider.class) {
                local = store;
                if (local == null) {
                    local = new OutboundFileStore(context.getApplicationContext().getCacheDir(),
                            System::currentTimeMillis, OutboundFileStore.DEFAULT_CAP_BYTES,
                            mime -> MimeTypeMap.getSingleton().getExtensionFromMimeType(mime));
                    store = local;
                }
            }
        }
        return local;
    }

    public static Uri uriFor(Context context, File file) {
        return new Uri.Builder().scheme("content").authority(authority(context))
                .encodedPath("/" + Uri.encode(store(context).relativePath(file), "/")).build();
    }

    @Override public boolean onCreate() {
        final Context context = getContext();
        new Thread(() -> store(context).sweep(), "apptwin-outbound-sweep").start();
        return true;
    }

    private File file(Uri uri) {
        String path = uri.getPath();
        if (path == null || !path.startsWith("/")) return null;
        return store(getContext()).resolve(path.substring(1));
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only");
        File file = file(uri);
        if (file == null) throw new FileNotFoundException("Not found");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        File file = file(uri);
        if (file == null) return null;
        String[] columns = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = file.getName();
            else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file.length();
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override public String getType(Uri uri) {
        File file = file(uri);
        if (file == null) return null;
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String mime = dot > 0 ? MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT)) : null;
        return mime != null ? mime : DEFAULT_MIME;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
