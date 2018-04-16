/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
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

import java.util.function.BiFunction;
import java.util.function.Supplier;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.impl.Accessor.EngineSupport;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.nodes.ExecutableNode;
import com.oracle.truffle.api.nodes.RootNode;

@SuppressWarnings("deprecation")
abstract class HostEntryRootNode<T> extends ExecutableNode implements Supplier<String> {

    HostEntryRootNode() {
        super(null);
    }

    protected abstract Class<? extends T> getReceiverType();

    @Override
    public final Object execute(VirtualFrame frame) {
        Object[] arguments = frame.getArguments();
        Object languageContext = arguments[0];
        T receiver = getReceiverType().cast(arguments[1]);
        Object result;
        result = executeImpl(languageContext, receiver, arguments, 2);
        assert languageContext == null || !(result instanceof TruffleObject);
        return result;
    }

    protected abstract Object executeImpl(Object languageContext, T receiver, Object[] args, int offset);

    protected static CallTarget createTarget(HostEntryRootNode<?> node) {
        EngineSupport support = JavaInteropAccessor.ACCESSOR.engine();
        if (support == null) {
            return Truffle.getRuntime().createCallTarget(new RootNode(null) {

                @Override
                public Object execute(VirtualFrame frame) {
                    return node.execute(frame);
                }
            });
        }
        return Truffle.getRuntime().createCallTarget(support.wrapHostBoundary(node, node));
    }

    protected static BiFunction<Object, Object, Object> createToGuestValueNode() {
        EngineSupport support = JavaInteropAccessor.ACCESSOR.engine();
        if (support == null) {
            return new BiFunction<Object, Object, Object>() {
                public Object apply(Object context, Object value) {
                    return asTruffleValue(value, context);
                }
            };
        }
        return support.createToGuestValueNode();
    }

    protected static BiFunction<Object, Object[], Object[]> createToGuestValuesNode() {
        EngineSupport support = JavaInteropAccessor.ACCESSOR.engine();
        if (support == null) {
            // legacy support
            return new BiFunction<Object, Object[], Object[]>() {
                public Object[] apply(Object context, Object[] values) {
                    for (int i = 0; i < values.length; i++) {
                        values[i] = asTruffleValue(values[i], context);
                    }
                    return values;
                }
            };
        }
        return support.createToGuestValuesNode();
    }

    static Object asTruffleValue(Object obj, Object languageContext) {
        return JavaInterop.isPrimitive(obj) ? obj : JavaInterop.asTruffleObject(obj, languageContext);
    }
}
