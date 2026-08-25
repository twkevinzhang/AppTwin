package com.lody.virtual.client.hook.proxies.keystore;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.StrongBoxUnavailableException;

import androidx.test.platform.app.InstrumentationRegistry;

import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.ProviderException;
import java.security.Signature;
import java.util.Calendar;
import java.util.Date;

import org.junit.Test;

/** Opt-in recovery proof for Facebook Lite's fixed local attestation signing key. */
public class FacebookLiteAttestationKeyDeviceTest {
    private static final String OPT_IN_ARGUMENT = "facebookLiteAttestationKeyE2e";
    private static final String PACKAGE_NAME = "com.facebook.lite";
    private static final int USER_ID = 1;
    private static final String GUEST_ALIAS = "w6CmevIyM/PL6Q5uUDw=";

    @Test
    public void provisionsNamespacedSigningKeyWhenMissing() throws Exception {
        assumeTrue("Facebook Lite key recovery is opt-in; pass -e "
                        + OPT_IN_ARGUMENT + " 1",
                "1".equals(InstrumentationRegistry.getArguments()
                        .getString(OPT_IN_ARGUMENT)));
        String physicalAlias = KeystoreAliasPolicy.toPhysicalAlias(
                PACKAGE_NAME, USER_ID, GUEST_ALIAS);
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (!keyStore.containsAlias(physicalAlias)) {
            generate(physicalAlias, true);
        }

        KeyStore.Entry entry = keyStore.getEntry(physicalAlias, null);
        assertTrue(entry instanceof KeyStore.PrivateKeyEntry);
        KeyStore.PrivateKeyEntry privateKeyEntry = (KeyStore.PrivateKeyEntry) entry;
        assertNotNull(privateKeyEntry.getCertificate());
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(privateKeyEntry.getPrivateKey());
        signature.update(new byte[]{1, 2, 3});
        assertTrue(signature.sign().length > 0);
    }

    private static void generate(String alias, boolean strongBox) throws Exception {
        Date notBefore = new Date();
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(notBefore);
        calendar.add(Calendar.YEAR, 10);
        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setCertificateNotBefore(notBefore)
                .setCertificateNotAfter(calendar.getTime());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(strongBox);
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
        try {
            generator.initialize(builder.build());
            generator.generateKeyPair();
        } catch (ProviderException error) {
            if (!strongBox || !(error instanceof StrongBoxUnavailableException)) throw error;
            generate(alias, false);
        }
    }
}
