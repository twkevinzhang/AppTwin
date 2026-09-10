package com.lody.virtual.server.pm.parser;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PackageInfoApplicationInfoSharingSourceTest {
    @Test
    public void packageComponentsReuseTopLevelApplicationInfo() throws Exception {
        String parser = read("src/main/java/com/lody/virtual/server/pm/parser/PackageParserEx.java");
        String packageInfo = between(
                parser,
                "public static PackageInfo generatePackageInfo(",
                "private static <T extends ComponentInfo> T shareApplicationInfo(");

        assertTrue(packageInfo.contains("pi.applicationInfo = generateApplicationInfo("));
        assertEquals(4, occurrences(packageInfo, "shareApplicationInfo("));
        assertEquals(4, occurrences(packageInfo, ", pi.applicationInfo);"));
    }

    @Test
    public void sharingChangesOnlyTheNestedApplicationInfoReference() throws Exception {
        String parser = read("src/main/java/com/lody/virtual/server/pm/parser/PackageParserEx.java");
        String helper = between(
                parser,
                "private static <T extends ComponentInfo> T shareApplicationInfo(",
                "public static ApplicationInfo generateApplicationInfo(");

        assertTrue(helper.contains("componentInfo.applicationInfo = applicationInfo;"));
        assertTrue(helper.contains("return componentInfo;"));
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static String between(String value, String start, String end) {
        int startIndex = value.indexOf(start);
        int endIndex = value.indexOf(end, startIndex);
        assertTrue(startIndex >= 0);
        assertTrue(endIndex > startIndex);
        return value.substring(startIndex, endIndex);
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }
}
