/*
 * Copyright (c) 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.api.interop.java;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;

abstract class WriteFieldNode extends Node {
    static final int LIMIT = 3;

    @Child ToJavaNode toJava = ToJavaNode.create();

    WriteFieldNode() {
    }

    static WriteFieldNode create() {
        return WriteFieldNodeGen.create();
    }

    public abstract void execute(JavaFieldDesc field, JavaObject object, Object value);

    @SuppressWarnings("unused")
    @Specialization(guards = {"field == cachedField"}, limit = "LIMIT")
    void doCached(SingleFieldDesc field, JavaObject object, Object rawValue,
                    @Cached("field") SingleFieldDesc cachedField) {
        Object val = toJava.execute(rawValue, cachedField.getType(), cachedField.getGenericType(), object.languageContext);
        cachedField.set(object.obj, val);
    }

    @Specialization(replaces = "doCached")
    void doUncached(SingleFieldDesc field, JavaObject object, Object rawValue) {
        Object val = toJava.execute(rawValue, field.getType(), field.getGenericType(), object.languageContext);
        field.set(object.obj, val);
    }
}
