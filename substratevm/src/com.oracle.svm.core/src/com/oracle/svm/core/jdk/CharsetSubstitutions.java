/*
 * Copyright (c) 2013, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.core.jdk;

// Checkstyle: allow reflection

import java.nio.charset.Charset;
import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import org.graalvm.nativeimage.ImageSingletons;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

import sun.misc.ASCIICaseInsensitiveComparator;

@TargetClass(java.nio.charset.Charset.class)
@SuppressWarnings({"unused"})
final class Target_java_nio_charset_Charset {

    @Alias private static Charset defaultCharset;

    @Substitute
    private static Charset defaultCharset() {
        /*
         * The default charset is initialized during native image generation and therefore always
         * available without any checks.
         */
        return defaultCharset;
    }

    @Substitute
    private static SortedMap<String, Charset> availableCharsets() {
        TreeMap<String, Charset> result = new TreeMap<>(ASCIICaseInsensitiveComparator.CASE_INSENSITIVE_ORDER);
        Map<String, Charset> charsets = ImageSingletons.lookup(LocalizationSupport.class).charsets;
        for (Charset charset : charsets.values()) {
            result.put(charset.name(), charset);
        }
        return Collections.unmodifiableSortedMap(result);
    }

    @Substitute
    private static Charset lookup(String charsetName) {
        if (charsetName == null) {
            throw new IllegalArgumentException("Null charset name");
        }

        Map<String, Charset> charsets = ImageSingletons.lookup(LocalizationSupport.class).charsets;
        String lowerCaseName = Target_sun_nio_cs_FastCharsetProvider.toLower(charsetName);

        Charset result = charsets.get(lowerCaseName);

        if (result == null) {
            /* Only need to check the name if we didn't find a charset for it */
            checkName(charsetName);
        }
        return result;
    }

    @Alias
    private static native void checkName(String s);

    @Substitute
    private static boolean atBugLevel(String bl) {
        return false;
    }
}

@TargetClass(sun.nio.cs.FastCharsetProvider.class)
final class Target_sun_nio_cs_FastCharsetProvider {

    @Alias
    protected static native String toLower(String s);
}

/** Dummy class to have a class with the file's name. */
public final class CharsetSubstitutions {
}
