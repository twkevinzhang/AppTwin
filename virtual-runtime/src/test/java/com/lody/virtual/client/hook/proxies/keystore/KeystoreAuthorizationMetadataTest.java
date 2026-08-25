package com.lody.virtual.client.hook.proxies.keystore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;

import org.junit.Test;

public class KeystoreAuthorizationMetadataTest {
    @Test
    public void extractsSecureUserIdsFromStableAidlShape() {
        FakeResponse response = new FakeResponse(new FakeAuthorization[]{
                authorization(KeystoreAuthorizationMetadata.USER_SECURE_ID_TAG, 101L),
                authorization(0x10000002, 202L),
                authorization(KeystoreAuthorizationMetadata.USER_SECURE_ID_TAG, 303L)
        });

        assertEquals(Arrays.asList(101L, 303L),
                KeystoreAuthorizationMetadata.keySecureUserIds(response));
    }

    @Test
    public void incompleteMetadataDoesNotProduceDeletionEvidence() {
        assertNull(KeystoreAuthorizationMetadata.keySecureUserIds(new Object()));
        assertNull(KeystoreAuthorizationMetadata.keySecureUserIds(
                new FakeResponse(null)));
    }

    private static FakeAuthorization authorization(int tag, long value) {
        return new FakeAuthorization(new FakeParameter(tag, new FakeValue(value)));
    }

    public static final class FakeResponse {
        public final FakeMetadata metadata;

        FakeResponse(FakeAuthorization[] authorizations) {
            metadata = new FakeMetadata(authorizations);
        }
    }

    public static final class FakeMetadata {
        public final FakeAuthorization[] authorizations;

        FakeMetadata(FakeAuthorization[] authorizations) {
            this.authorizations = authorizations;
        }
    }

    public static final class FakeAuthorization {
        public final FakeParameter keyParameter;

        FakeAuthorization(FakeParameter keyParameter) {
            this.keyParameter = keyParameter;
        }
    }

    public static final class FakeParameter {
        public final int tag;
        public final FakeValue value;

        FakeParameter(int tag, FakeValue value) {
            this.tag = tag;
            this.value = value;
        }
    }

    public static final class FakeValue {
        private final long value;

        FakeValue(long value) {
            this.value = value;
        }

        public long getLongInteger() {
            return value;
        }
    }
}
