package mirror.android.app;

import static org.junit.Assert.assertArrayEquals;

import android.os.IBinder;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.List;

import mirror.MethodParams;
import mirror.RefMethod;

public class ActivityThreadNewIntentMirrorTest {
    @Test
    public void productionMappingsDeclareExactAndroidDescriptors() throws Exception {
        assertMapping(ActivityThread.class, "handleNewIntent", IBinder.class, List.class);
        assertMapping(ActivityThreadS.class, "getActivityClient", IBinder.class);
        assertMapping(ActivityThreadS.class, "handleNewIntent",
                ActivityThread.ActivityClientRecord.class, List.class);
    }

    @Test
    public void annotatedDescriptorsIgnoreOverloadDeclarationOrder() throws Exception {
        assertDescriptor(TokenFirstActivityThread.class, TokenMapping.class,
                FakeActivityToken.class, List.class);
        assertDescriptor(RecordFirstActivityThread.class, TokenMapping.class,
                FakeActivityToken.class, List.class);
        assertDescriptor(TokenFirstActivityThread.class, RecordMapping.class,
                FakeActivityClientRecord.class, List.class);
        assertDescriptor(RecordFirstActivityThread.class, RecordMapping.class,
                FakeActivityClientRecord.class, List.class);
    }

    private static void assertMapping(Class<?> mappingClass, String fieldName,
                                      Class<?>... expectedParameters) throws Exception {
        MethodParams annotation = mappingClass.getDeclaredField(fieldName)
                .getAnnotation(MethodParams.class);
        assertArrayEquals(expectedParameters, annotation.value());
    }

    private static void assertDescriptor(Class<?> targetClass, Class<?> mappingClass,
                                         Class<?>... expectedParameters) throws Exception {
        Field mapping = mappingClass.getDeclaredField("handleNewIntent");
        RefMethod<Void> method = new RefMethod<>(targetClass, mapping);
        assertArrayEquals(expectedParameters, method.paramList());
    }

    private static class TokenMapping {
        @MethodParams({FakeActivityTokenMirror.class, List.class})
        public static RefMethod<Void> handleNewIntent;
    }

    private static class RecordMapping {
        @MethodParams({FakeActivityClientRecordMirror.class, List.class})
        public static RefMethod<Void> handleNewIntent;
    }

    public static class FakeActivityClientRecordMirror {
        public static Class<?> TYPE = FakeActivityClientRecord.class;
    }

    public static class FakeActivityTokenMirror {
        public static Class<?> TYPE = FakeActivityToken.class;
    }

    private static class FakeActivityToken {
    }

    private static class FakeActivityClientRecord {
    }

    private static class TokenFirstActivityThread {
        @SuppressWarnings("unused")
        private void handleNewIntent(FakeActivityToken token, List<?> intents) {
        }

        @SuppressWarnings("unused")
        private void handleNewIntent(FakeActivityClientRecord record, List<?> intents) {
        }
    }

    private static class RecordFirstActivityThread {
        @SuppressWarnings("unused")
        private void handleNewIntent(FakeActivityClientRecord record, List<?> intents) {
        }

        @SuppressWarnings("unused")
        private void handleNewIntent(FakeActivityToken token, List<?> intents) {
        }
    }
}
