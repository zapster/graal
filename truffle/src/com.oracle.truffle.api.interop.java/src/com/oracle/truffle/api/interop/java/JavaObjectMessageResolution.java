/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
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

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Objects;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleOptions;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.ForeignAccess;
import com.oracle.truffle.api.interop.Message;
import com.oracle.truffle.api.interop.MessageResolution;
import com.oracle.truffle.api.interop.Resolve;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.nodes.Node;

@MessageResolution(receiverType = JavaObject.class)
class JavaObjectMessageResolution {

    @Resolve(message = "GET_SIZE")
    abstract static class ArrayGetSizeNode extends Node {

        public Object access(JavaObject receiver) {
            Object obj = receiver.obj;
            if (obj == null) {
                return 0;
            }
            try {
                return Array.getLength(obj);
            } catch (IllegalArgumentException e) {
                CompilerDirectives.transferToInterpreter();
                throw UnsupportedMessageException.raise(Message.GET_SIZE);
            }
        }

    }

    @Resolve(message = "HAS_SIZE")
    abstract static class ArrayHasSizeNode extends Node {

        public Object access(JavaObject receiver) {
            Object obj = receiver.obj;
            if (obj == null) {
                return false;
            }
            return obj.getClass().isArray();
        }

    }

    @Resolve(message = "INVOKE")
    abstract static class InvokeNode extends Node {

        @Child private ExecuteMethodNode doExecute;
        @Child private Node sendIsExecutableNode;
        @Child private Node sendExecuteNode;

        public Object access(JavaObject object, String name, Object[] args) {
            if (TruffleOptions.AOT) {
                throw UnsupportedMessageException.raise(Message.createInvoke(args.length));
            }

            // (1) look for a method; if found, invoke it on obj.
            JavaMethodDesc foundMethod = JavaInteropReflect.findMethod(object, name, args);
            if (foundMethod != null) {
                if (doExecute == null) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    doExecute = insert(ExecuteMethodNode.create());
                }
                return doExecute.execute(foundMethod, object.obj, args, object.languageContext);
            }

            // (2) look for a field; if found, read its value and if that IsExecutable, Execute it.
            Field foundField = JavaInteropReflect.findField(object, name);
            if (foundField != null) {
                Object fieldValue = JavaInteropReflect.readField(object, name);
                if (!JavaInterop.isPrimitive(fieldValue)) {
                    TruffleObject fieldObject = JavaInterop.asTruffleObject(fieldValue, object.languageContext);

                    if (sendIsExecutableNode == null) {
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        sendIsExecutableNode = insert(Message.IS_EXECUTABLE.createNode());
                    }
                    if (sendExecuteNode == null) {
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        sendExecuteNode = insert(Message.createExecute(args.length).createNode());
                    }
                    boolean isExecutable = ForeignAccess.sendIsExecutable(sendIsExecutableNode, fieldObject);
                    if (isExecutable) {
                        try {
                            return ForeignAccess.sendExecute(sendExecuteNode, fieldObject, args);
                        } catch (UnsupportedTypeException | ArityException | UnsupportedMessageException e) {
                            throw e.raise();
                        }
                    }
                }
            }

            throw UnknownIdentifierException.raise(name);
        }
    }

    @Resolve(message = "NEW")
    abstract static class NewNode extends Node {
        @Child private ExecuteMethodNode doExecute;
        @Child private ToJavaNode toJava;
        private static final TypeAndClass<Integer> INT_TYPE = new TypeAndClass<>(null, int.class);

        public Object access(JavaObject receiver, Object[] args) {
            if (TruffleOptions.AOT) {
                throw UnsupportedMessageException.raise(Message.createNew(args.length));
            }

            if (receiver.isClass()) {
                if (receiver.clazz.isArray()) {
                    if (args.length != 1) {
                        throw ArityException.raise(1, args.length);
                    }
                    if (toJava == null) {
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        toJava = insert(ToJavaNode.create());
                    }
                    int length = (int) toJava.execute(args[0], INT_TYPE, receiver.languageContext);
                    return JavaInterop.asTruffleObject(Array.newInstance(receiver.clazz.getComponentType(), length), receiver.languageContext);
                }

                JavaClassDesc classDesc = JavaClassDesc.forClass(receiver.clazz);
                JavaMethodDesc method = classDesc.lookupConstructor();
                if (method != null) {
                    if (doExecute == null) {
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        doExecute = insert(ExecuteMethodNode.create());
                    }
                    return doExecute.execute(method, null, args, receiver.languageContext);
                }
            }
            throw UnsupportedTypeException.raise(new Object[]{receiver});
        }
    }

    @Resolve(message = "IS_NULL")
    abstract static class NullCheckNode extends Node {

        public Object access(JavaObject object) {
            return object == JavaObject.NULL;
        }

    }

    @Resolve(message = "IS_BOXED")
    abstract static class BoxedCheckNode extends Node {
        @Child private ToPrimitiveNode primitive = ToPrimitiveNode.create();

        public Object access(JavaObject object) {
            return JavaInterop.isPrimitive(object.obj);
        }

    }

    @Resolve(message = "UNBOX")
    abstract static class UnboxNode extends Node {
        @Child private ToPrimitiveNode primitive = ToPrimitiveNode.create();

        public Object access(JavaObject object) {
            if (JavaInterop.isPrimitive(object.obj)) {
                return object.obj;
            } else {
                return UnsupportedMessageException.raise(Message.UNBOX);
            }
        }

    }

    @Resolve(message = "READ")
    abstract static class ReadFieldNode extends Node {

        @Child private ArrayReadNode read = ArrayReadNode.create();

        public Object access(JavaObject object, Number index) {
            return read.executeWithTarget(object, index);
        }

        @TruffleBoundary
        public Object access(JavaObject object, String name) {
            if (object.obj instanceof Map) {
                return accessMap(object, name);
            }
            if (TruffleOptions.AOT) {
                return JavaObject.NULL;
            }
            return JavaInteropReflect.readField(object, name);
        }

        @TruffleBoundary
        private static Object accessMap(JavaObject object, String name) {
            Map<?, ?> map = (Map<?, ?>) object.obj;
            return JavaInterop.asTruffleValue(map.get(name));
        }
    }

    @Resolve(message = "WRITE")
    abstract static class WriteFieldNode extends Node {

        @Child private ToJavaNode toJava = ToJavaNode.create();
        @Child private ArrayWriteNode write = ArrayWriteNode.create();

        @TruffleBoundary
        public Object access(JavaObject receiver, String name, Object value) {
            Object obj = receiver.obj;
            if (obj instanceof Map) {
                return accessMap(receiver, name, value);
            }
            if (TruffleOptions.AOT) {
                throw UnsupportedMessageException.raise(Message.WRITE);
            }
            Field f = JavaInteropReflect.findField(receiver, name);
            if (f == null) {
                throw UnknownIdentifierException.raise(name);
            }
            Object convertedValue = toJava.execute(value, new TypeAndClass<>(f.getGenericType(), f.getType()), receiver.languageContext);
            JavaInteropReflect.setField(obj, f, convertedValue);
            return JavaObject.NULL;
        }

        @TruffleBoundary
        @SuppressWarnings("unchecked")
        private Object accessMap(JavaObject receiver, String name, Object value) {
            Map<Object, Object> map = (Map<Object, Object>) receiver.obj;
            Object convertedValue = toJava.execute(value, TypeAndClass.ANY, receiver.languageContext);
            return map.put(name, convertedValue);
        }

        public Object access(JavaObject receiver, Number index, Object value) {
            return write.executeWithTarget(receiver, index, value);
        }

    }

    @Resolve(message = "KEYS")
    abstract static class PropertiesNode extends Node {
        @TruffleBoundary
        public Object access(JavaObject receiver, boolean includeInternal) {
            String[] fields;
            if (receiver.obj instanceof Map) {
                fields = accessMap(receiver);
            } else {
                fields = TruffleOptions.AOT ? new String[0] : JavaInteropReflect.findUniquePublicMemberNames(receiver.clazz, !receiver.isClass(), includeInternal);
            }
            return JavaInterop.asTruffleObject(fields);
        }

        @TruffleBoundary
        private static String[] accessMap(JavaObject receiver) {
            Map<?, ?> map = (Map<?, ?>) receiver.obj;
            String[] fields = new String[map.size()];
            int i = 0;
            for (Object key : map.keySet()) {
                fields[i++] = Objects.toString(key, null);
            }
            return fields;
        }
    }

    @Resolve(message = "KEY_INFO")
    abstract static class PropertyInfoNode extends Node {

        @TruffleBoundary
        public int access(JavaObject receiver, Number index) {
            int i = index.intValue();
            if (i != index.doubleValue()) {
                // No non-integer indexes
                return 0;
            }
            if (i < 0) {
                return 0;
            }
            if (receiver.isArray()) {
                int length = Array.getLength(receiver.obj);
                if (i < length) {
                    return 0b111;
                }
            }
            return 0;
        }

        @TruffleBoundary
        public int access(JavaObject receiver, String name) {
            if (receiver.obj instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) receiver.obj;
                if (map.containsKey(name)) {
                    return 0b111;
                } else {
                    return 0;
                }
            }
            if (TruffleOptions.AOT) {
                return 0;
            }
            if (JavaInteropReflect.isField(receiver, name)) {
                return 0b111;
            }
            if (JavaInteropReflect.isMethod(receiver, name)) {
                if (JavaInteropReflect.isInternalMethod(receiver, name)) {
                    return 0b11111;
                } else {
                    return 0b1111;
                }
            }
            if (JavaInteropReflect.isMemberType(receiver, name)) {
                return 0b11;
            }
            return 0;
        }
    }

    @Resolve(message = "IS_EXECUTABLE")
    abstract static class IsExecutableObjectNode extends Node {

        public Object access(JavaObject receiver) {
            if (TruffleOptions.AOT) {
                return false;
            }
            return receiver.obj != null && JavaClassDesc.forClass(receiver.clazz).implementsFunctionalInterface();
        }
    }

    @Resolve(message = "EXECUTE")
    abstract static class ExecuteObjectNode extends Node {
        @Child private ExecuteMethodNode doExecute;

        public Object access(JavaObject receiver, Object[] args) {
            if (TruffleOptions.AOT) {
                throw UnsupportedMessageException.raise(Message.createExecute(args.length));
            }
            if (receiver.obj != null) {
                assert !receiver.isClass();
                JavaMethodDesc method = JavaClassDesc.forClass(receiver.clazz).getFunctionalMethod();
                if (method != null) {
                    if (doExecute == null) {
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        doExecute = insert(ExecuteMethodNode.create());
                    }
                    return doExecute.execute(method, receiver.obj, args, receiver.languageContext);
                }
            }
            throw UnsupportedMessageException.raise(Message.createExecute(args.length));
        }
    }
}
