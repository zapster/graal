/*
 * Copyright (c) 2016, 2016, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.tck;

import com.oracle.truffle.api.interop.KeyInfo;
import com.oracle.truffle.api.interop.MessageResolution;
import com.oracle.truffle.api.interop.Resolve;
import com.oracle.truffle.api.interop.java.JavaInterop;
import com.oracle.truffle.api.nodes.Node;

@MessageResolution(receiverType = StructuredDataEntry.class)
class StructuredDataEntryMessageResolution {

    @Resolve(message = "KEYS")
    abstract static class StructuredDataEntryKeysNode extends Node {

        public Object access(StructuredDataEntry data) {
            return JavaInterop.asTruffleObject(data.getSchema().getNames());
        }
    }

    @Resolve(message = "KEY_INFO")
    abstract static class StructuredDataEntryKeyInfoNode extends Node {

        private static final int readable = KeyInfo.newBuilder().setReadable(true).build();

        public Object access(StructuredDataEntry data, Object identifier) {
            if (data.getSchema().getNames().contains(identifier)) {
                return readable;
            } else {
                return 0;
            }
        }
    }

    @Resolve(message = "READ")
    abstract static class StructuredDataEntryReadNode extends Node {

        public Object access(StructuredDataEntry data, String name) {
            return data.getSchema().get(data.getBuffer(), data.getIndex(), name);
        }

    }

}
