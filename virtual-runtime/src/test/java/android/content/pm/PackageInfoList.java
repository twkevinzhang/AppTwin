package android.content.pm;

import java.util.List;

/** Test substitute for the API 37 framework class, which is absent from compileSdk 36. */
public final class PackageInfoList {
    private final List<?> list;

    public PackageInfoList(List<?> list) {
        this.list = list;
    }

    public List<?> getList() {
        return list;
    }
}
