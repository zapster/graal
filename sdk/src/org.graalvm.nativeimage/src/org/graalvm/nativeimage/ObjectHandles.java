/*
 * Copyright (c) 2013, 2017, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.nativeimage;

import org.graalvm.nativeimage.impl.ObjectHandlesSupport;

/**
 * Manages a set of {@link ObjectHandles}. The handles returned by {@link #create} are bound to the
 * creating handle set, i.e., the handle can only be {@link #get accessed} and {@link #destroy
 * destroyed} using the exact same handle set used for creation.
 */
public abstract class ObjectHandles {

    /**
     * A set of handles that is kept alive globally.
     */
    public static ObjectHandles getGlobal() {
        return ImageSingletons.lookup(ObjectHandlesSupport.class).getGlobalHandles();
    }

    /**
     * Creates a new set of handles. Objects are kept alive until the returned {@link ObjectHandles}
     * instance gets unreachable.
     */
    public static ObjectHandles create() {
        return ImageSingletons.lookup(ObjectHandlesSupport.class).createHandles();
    }

    /**
     * Creates a handle to the specified object. The object is kept alive by the garbage collector
     * at least until {@link #destroy} is called for the returned handle. The object can be null.
     */
    public abstract ObjectHandle create(Object object);

    /**
     * Extracts the object from a given handle.
     */
    public abstract <T> T get(ObjectHandle handle);

    /**
     * Destroys the given global handle. After calling this method, the handle must not be used
     * anymore.
     */
    public abstract void destroy(ObjectHandle handle);
}
