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
package org.graalvm.polyglot.proxy;

import java.util.Map;

import org.graalvm.polyglot.Value;

/**
 *
 *
 * @since 1.0
 */
public interface ProxyObject extends Proxy {

    /**
     *
     *
     * @since 1.0
     */
    Object getMember(String key);

    /**
     *
     *
     * @since 1.0
     */
    ProxyArray getMemberKeys();

    /**
     *
     *
     * @since 1.0
     */
    boolean hasMember(String key);

    /**
     *
     *
     * @since 1.0
     */
    void putMember(String key, Value value);

    /**
     *
     *
     * @since 1.0
     */
    static ProxyObject fromMap(Map<String, Object> values) {
        return new ProxyObject() {

            public void putMember(String key, Value value) {
                values.put(key, value.isHostObject() ? value.asHostObject() : value);
            }

            public boolean hasMember(String key) {
                return values.containsKey(key);
            }

            public ProxyArray getMemberKeys() {
                return ProxyArray.fromArray(values.keySet().toArray());
            }

            public Object getMember(String key) {
                return values.get(key);
            }
        };
    }

}
