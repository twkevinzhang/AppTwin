package com.lody.virtual.client.stub;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.BeforeClass;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class DaemonServiceManifestPolicyTest {
    private static final String ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android";
    private static final String DAEMON_SERVICE =
            "com.lody.virtual.client.stub.DaemonService";
    private static Document manifest;

    @BeforeClass
    public static void parseManifest() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        manifest = factory.newDocumentBuilder().parse(new File("src/main/AndroidManifest.xml"));
    }

    @Test
    public void declaresSpecialUseForegroundServicePermissions() {
        assertTrue(hasPermission("android.permission.FOREGROUND_SERVICE"));
        assertTrue(hasPermission("android.permission.FOREGROUND_SERVICE_SPECIAL_USE"));
    }

    @Test
    public void daemonDeclaresPrivateSpecialUseTypeAndSubtype() {
        Element service = findElementByAndroidName("service", DAEMON_SERVICE);

        assertNotNull(service);
        assertEquals("false", service.getAttributeNS(ANDROID_NAMESPACE, "exported"));
        assertEquals("specialUse",
                service.getAttributeNS(ANDROID_NAMESPACE, "foregroundServiceType"));

        Element property = findChildByAndroidName(
                service, "property", "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE");
        assertNotNull(property);
        assertTrue(!property.getAttributeNS(ANDROID_NAMESPACE, "value").trim().isEmpty());
    }

    private static boolean hasPermission(String permission) {
        return findElementByAndroidName("uses-permission", permission) != null;
    }

    private static Element findElementByAndroidName(String tagName, String androidName) {
        NodeList elements = manifest.getElementsByTagName(tagName);
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            if (androidName.equals(element.getAttributeNS(ANDROID_NAMESPACE, "name"))) {
                return element;
            }
        }
        return null;
    }

    private static Element findChildByAndroidName(
            Element parent, String tagName, String androidName) {
        NodeList elements = parent.getElementsByTagName(tagName);
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            if (androidName.equals(element.getAttributeNS(ANDROID_NAMESPACE, "name"))) {
                return element;
            }
        }
        return null;
    }
}
