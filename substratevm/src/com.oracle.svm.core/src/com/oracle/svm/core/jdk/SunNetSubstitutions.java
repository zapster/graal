/*
 * Copyright (c) 2017, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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

import java.io.FileDescriptor;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

import jdk.net.SocketFlow;

@TargetClass(className = "sun.net.ExtendedOptionsImpl", onlyWith = JDK8OrEarlier.class)
final class Target_sun_net_ExtendedOptionsImpl {

    /* private static native void init() */
    @Substitute
    private static void init() {
        /* Nothing to do. */
    }

    /* public static native boolean flowSupported() */
    @Substitute
    private static boolean flowSupported() {
        return false;
    }

    /* public static native void setFlowOption(FileDescriptor fd, SocketFlow f) */
    @Substitute
    @SuppressWarnings({"unused"})
    private static void setFlowOption(FileDescriptor fd, SocketFlow f) {
        throw new UnsupportedOperationException("Target_sun_net_ExtendedOptionsImpl.setFlowOptions");
    }

    /* public static native void getFlowOption(FileDescriptor fd, SocketFlow f) */
    @Substitute
    @SuppressWarnings({"unused"})
    private static void getFlowOption(FileDescriptor fd, SocketFlow f) {
        throw new UnsupportedOperationException("Target_sun_net_ExtendedOptionsImpl.getFlowOptions");
    }
}

/** Dummy class to have a class with the file's name. */
public final class SunNetSubstitutions {
}
