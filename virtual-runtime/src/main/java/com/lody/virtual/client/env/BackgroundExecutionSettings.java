package com.lody.virtual.client.env;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Cross-process durable user intent. Reads never use a process-local preference cache. */
public final class BackgroundExecutionSettings {
    private BackgroundExecutionSettings() {}
    private static AtomicFile file(Context context) {
        return new AtomicFile(new File(context.getFilesDir(), "background_execution_enabled"));
    }
    public static boolean isEnabled(Context context) {
        return readEnabled(new File(context.getFilesDir(), "background_execution_enabled"));
    }

    static boolean readEnabled(File base) {
        // AtomicFile.openRead() may delete a writer's live .new file. Readers in other
        // processes must be strictly read-only; rename makes the one-byte base snapshot atomic.
        File backup = new File(base.getPath() + ".bak");
        // Legacy AtomicFile writes the new value into base while retaining the committed
        // value in .bak. Prefer that committed snapshot until the writer removes its backup.
        if (backup.exists()) {
            try (java.io.InputStream input = new java.io.FileInputStream(backup)) {
                return input.read() == 1 && input.read() == -1;
            } catch (IOException error) { return false; }
        }
        try (java.io.InputStream input = new java.io.FileInputStream(base)) {
            return input.read() == 1 && input.read() == -1;
        } catch (java.io.FileNotFoundException missing) {
            return !base.exists() && !backup.exists()
                    && !new File(base.getPath() + ".new").exists();
        } catch (IOException error) { return false; }
    }
    public static void setEnabled(Context context, boolean enabled) {
        AtomicFile file = file(context);
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(enabled ? 1 : 0);
            file.finishWrite(output);
        } catch (IOException error) {
            if (output != null) file.failWrite(output);
            throw new IllegalStateException("Unable to save background execution setting", error);
        }
    }
}
