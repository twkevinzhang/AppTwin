package mirror.android.security.net.config;

import mirror.RefClass;
import mirror.RefStaticMethod;

public class NetworkSecurityConfigProvider {
    public static Class<?> TYPE = RefClass.load(NetworkSecurityConfigProvider.class,
            "android.security.net.config.NetworkSecurityConfigProvider");

    public static RefStaticMethod<Void> handleNewApplication;
}
