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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.TruffleOptions;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;

interface JavaFieldDesc {
    String getName();

    default boolean isInternal() {
        return false;
    }
}

abstract class SingleFieldDesc implements JavaFieldDesc {
    private final Class<?> type;
    private final Type genericType;

    protected SingleFieldDesc(Class<?> type, Type genericType) {
        this.type = type;
        this.genericType = genericType;
    }

    public final Class<?> getType() {
        return type;
    }

    public final Type getGenericType() {
        return genericType;
    }

    public abstract Object get(Object receiver);

    public abstract void set(Object receiver, Object value);

    static JavaFieldDesc unreflect(Field reflectionField) {
        assert isAccessible(reflectionField);
        if (TruffleOptions.AOT) {
            return new ReflectImpl(reflectionField);
        } else {
            return new MHImpl(reflectionField);
        }
    }

    static boolean isAccessible(Field field) {
        return Modifier.isPublic(field.getModifiers()) && Modifier.isPublic(field.getDeclaringClass().getModifiers());
    }

    private static final class ReflectImpl extends SingleFieldDesc {
        private final Field field;

        ReflectImpl(Field field) {
            super(field.getType(), field.getGenericType());
            this.field = field;
        }

        public String getName() {
            return field.getName();
        }

        @Override
        public Object get(Object receiver) {
            try {
                return reflectGet(field, receiver);
            } catch (IllegalArgumentException e) {
                throw UnsupportedTypeException.raise(e, JavaInteropReflect.EMPTY);
            } catch (IllegalAccessException e) {
                CompilerDirectives.transferToInterpreter();
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void set(Object receiver, Object value) {
            try {
                reflectSet(field, receiver, value);
            } catch (IllegalArgumentException e) {
                throw UnsupportedTypeException.raise(e, JavaInteropReflect.EMPTY);
            } catch (IllegalAccessException e) {
                CompilerDirectives.transferToInterpreter();
                if (Modifier.isFinal(field.getModifiers())) {
                    throw UnknownIdentifierException.raise(field.getName());
                } else {
                    throw new IllegalStateException(e);
                }
            }
        }

        @TruffleBoundary
        private static Object reflectGet(Field field, Object receiver) throws IllegalArgumentException, IllegalAccessException {
            return field.get(receiver);
        }

        @TruffleBoundary
        private static void reflectSet(Field field, Object receiver, Object value) throws IllegalArgumentException, IllegalAccessException {
            field.set(receiver, value);
        }

        @Override
        public String toString() {
            return "Field[" + field.toString() + "]";
        }
    }

    private static final class MHImpl extends SingleFieldDesc {
        private final Field field;
        @CompilationFinal private MethodHandle getHandle;
        @CompilationFinal private MethodHandle setHandle;

        MHImpl(Field field) {
            super(field.getType(), field.getGenericType());
            this.field = field;
        }

        public String getName() {
            return field.getName();
        }

        @Override
        public Object get(Object receiver) {
            if (getHandle == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                getHandle = makeGetMethodHandle();
            }
            try {
                return invokeGetHandle(getHandle, receiver);
            } catch (Exception e) {
                throw UnsupportedTypeException.raise(e, JavaInteropReflect.EMPTY);
            } catch (Throwable e) {
                throw JavaInteropReflect.rethrow(e);
            }
        }

        @Override
        public void set(Object receiver, Object value) {
            if (setHandle == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                setHandle = makeSetMethodHandle();
            }
            try {
                invokeSetHandle(setHandle, receiver, value);
            } catch (Exception e) {
                throw UnsupportedTypeException.raise(e, new Object[]{value});
            } catch (Throwable e) {
                throw JavaInteropReflect.rethrow(e);
            }
        }

        @TruffleBoundary(allowInlining = true)
        private static Object invokeGetHandle(MethodHandle invokeHandle, Object receiver) throws Throwable {
            return invokeHandle.invokeExact(receiver);
        }

        @TruffleBoundary(allowInlining = true)
        private static void invokeSetHandle(MethodHandle invokeHandle, Object receiver, Object value) throws Throwable {
            invokeHandle.invokeExact(receiver, value);
        }

        private MethodHandle makeGetMethodHandle() {
            try {
                if (Modifier.isStatic(field.getModifiers())) {
                    MethodHandle getter = MethodHandles.publicLookup().findStaticGetter(field.getDeclaringClass(), field.getName(), field.getType());
                    return MethodHandles.dropArguments(getter.asType(MethodType.methodType(Object.class)), 0, Object.class);
                } else {
                    MethodHandle getter = MethodHandles.publicLookup().findGetter(field.getDeclaringClass(), field.getName(), field.getType());
                    return getter.asType(MethodType.methodType(Object.class, Object.class));
                }
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        private MethodHandle makeSetMethodHandle() {
            try {
                if (Modifier.isStatic(field.getModifiers())) {
                    MethodHandle setter = MethodHandles.publicLookup().findStaticSetter(field.getDeclaringClass(), field.getName(), field.getType());
                    return MethodHandles.dropArguments(setter.asType(MethodType.methodType(void.class, Object.class)), 0, Object.class);
                } else {
                    MethodHandle setter = MethodHandles.publicLookup().findSetter(field.getDeclaringClass(), field.getName(), field.getType());
                    return setter.asType(MethodType.methodType(void.class, Object.class, Object.class));
                }
            } catch (NoSuchFieldException e) {
                throw UnknownIdentifierException.raise(field.getName());
            } catch (IllegalAccessException e) {
                if (Modifier.isFinal(field.getModifiers())) {
                    throw UnknownIdentifierException.raise(field.getName());
                } else {
                    throw new IllegalStateException(e);
                }
            }
        }

        @Override
        public String toString() {
            return "Field[" + field.toString() + "]";
        }
    }
}
