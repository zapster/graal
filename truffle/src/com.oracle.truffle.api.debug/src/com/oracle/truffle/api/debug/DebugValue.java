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
package com.oracle.truffle.api.debug;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.oracle.truffle.api.instrumentation.TruffleInstrument;
import com.oracle.truffle.api.interop.ForeignAccess;
import com.oracle.truffle.api.interop.KeyInfo;
import com.oracle.truffle.api.interop.Message;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.nodes.LanguageInfo;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;

/**
 * Represents a value accessed using the debugger API. Please note that values can become invalid
 * depending on the context in which they are used. For example stack values will only remain valid
 * as long as the current stack element is active. Heap values on the other hand remain valid. If a
 * value becomes invalid then setting or getting a value will throw an {@link IllegalStateException}
 * . {@link DebugValue} instances neither support equality or preserve identity.
 * <p>
 * Clients may access the debug value only on the execution thread where the suspended event of the
 * stack frame was created and notification received; access from other threads will throw
 * {@link IllegalStateException}.
 *
 * @since 0.17
 */
public abstract class DebugValue {

    final LanguageInfo preferredLanguage;

    abstract Object get();

    DebugValue(LanguageInfo preferredLanguage) {
        this.preferredLanguage = preferredLanguage;
    }

    /**
     * Sets the value using another {@link DebugValue}. Throws an {@link IllegalStateException} if
     * the value is not writable, the passed value is not readable, this value or the passed value
     * is invalid, or the guest language of the values do not match. Use
     * {@link DebugStackFrame#eval(String)} to evaluate values to be set.
     *
     * @param value the value to set
     * @since 0.17
     */
    public abstract void set(DebugValue value);

    /**
     * Converts the debug value into a Java type. Class conversions which are always supported:
     * <ul>
     * <li>{@link String}.class converts the value to its language specific string representation.
     * </li>
     * </ul>
     * No optional conversions are currently available. If a conversion is not supported then an
     * {@link UnsupportedOperationException} is thrown. If the value is not {@link #isReadable()
     * readable} then an {@link IllegalStateException} is thrown.
     *
     * @param clazz the type to convert to
     * @return the converted Java type
     * @since 0.17
     */
    public abstract <T> T as(Class<T> clazz);

    /**
     * Returns the name of this value as it is referred to from its origin. If this value is
     * originated from the stack it returns the name of the local variable. If the value was
     * returned from another objects then it returns the name of the property or field it is
     * contained in. If no name is available <code>null</code> is returned.
     *
     * @since 0.17
     */
    public abstract String getName();

    /**
     * Returns <code>true</code> if this value can be read else <code>false</code>.
     *
     * @see #as(Class)
     * @since 0.17
     */
    public abstract boolean isReadable();

    /**
     * Returns <code>true</code> if this value can be read else <code>false</code>.
     *
     * @see #as(Class)
     * @since 0.17
     * @deprecated Use {@link #isWritable()}
     */
    @Deprecated
    public final boolean isWriteable() {
        return isWritable();
    }

    /**
     * Returns <code>true</code> if this value can be written to, else <code>false</code>.
     *
     * @see #as(Class)
     * @since 0.26
     */
    public abstract boolean isWritable();

    /**
     * Returns <code>true</code> if this value represents an internal variable or property,
     * <code>false</code> otherwise.
     * <p>
     * Languages might have extra object properties or extra scope variables that are a part of the
     * runtime, but do not correspond to anything what is an explicit part of the guest language
     * representation. They may represent additional language artifacts, providing more in-depth
     * information that can be valuable during debugging. Language implementors mark these variables
     * as <em>internal</em>. An example of such internal values are internal slots in ECMAScript.
     * </p>
     *
     * @since 0.26
     */
    public abstract boolean isInternal();

    /**
     * Get the scope where this value is declared in. It returns a non-null value for local
     * variables declared on a stack. It's <code>null<code> for object properties and other heap
     * values.
     *
     * @return the scope, or <code>null</code> when this value does not belong into any scope.
     *
     * @since 0.26
     */
    public DebugScope getScope() {
        return null;
    }

    /**
     * Provides properties representing an internal structure of this value. The returned collection
     * is not thread-safe. If the value is not {@link #isReadable() readable} then an
     * {@link IllegalStateException} is thrown.
     *
     * @return a collection of property values, or </code>null</code> when the value does not have
     *         any concept of properties.
     * @since 0.19
     */
    public final Collection<DebugValue> getProperties() {
        if (!isReadable()) {
            throw new IllegalStateException("Value is not readable");
        }
        Object value = get();
        return getProperties(value, getDebugger(), getSourceRoot(), null);
    }

    static ValuePropertiesCollection getProperties(Object value, Debugger debugger, RootNode root, DebugScope scope) {
        ValuePropertiesCollection properties = null;
        if (value instanceof TruffleObject) {
            TruffleObject object = (TruffleObject) value;
            Map<Object, Object> map = ObjectStructures.asMap(debugger.getMessageNodes(), object);
            if (map != null) {
                properties = new ValuePropertiesCollection(debugger, root, object, map, map.entrySet(), scope);
            }
        }
        return properties;
    }

    /*
     * TODO future API: Find a property value based on a String name. In general, not all properties
     * may have String names. Use this for lookup of a value of some known String-based property.
     * DebugValue findProperty(String name)
     */

    /**
     * Returns <code>true</code> if this value represents an array, <code>false</code> otherwise.
     *
     * @since 0.19
     */
    public final boolean isArray() {
        Object value = get();
        if (value instanceof TruffleObject) {
            TruffleObject to = (TruffleObject) value;
            return ObjectStructures.isArray(getDebugger().getMessageNodes(), to);
        } else {
            return false;
        }
    }

    /**
     * Provides array elements when this value represents an array. To test if this value represents
     * an array, check {@link #isArray()}.
     *
     * @return a list of array elements, or <code>null</code> when the value does not represent an
     *         array.
     * @since 0.19
     */
    public final List<DebugValue> getArray() {
        List<DebugValue> arrayList = null;
        Object value = get();
        if (value instanceof TruffleObject) {
            TruffleObject to = (TruffleObject) value;
            List<Object> array = ObjectStructures.asList(getDebugger().getMessageNodes(), to);
            if (array != null) {
                arrayList = new ValueInteropList(getDebugger(), getSourceRoot(), array);
            }
        }
        return arrayList;
    }

    /**
     * Get a meta-object of this value, if any. The meta-object represents a description of the
     * value, reveals it's kind and it's features.
     *
     * @return a value representing the meta-object, or <code>null</code>
     * @since 0.22
     */
    public final DebugValue getMetaObject() {
        Object obj = get();
        if (obj == null) {
            return null;
        }
        TruffleInstrument.Env env = getDebugger().getEnv();
        LanguageInfo languageInfo;
        if (preferredLanguage != null) {
            languageInfo = preferredLanguage;
        } else {
            languageInfo = getSourceRoot().getRootNode().getLanguageInfo();
        }
        if (languageInfo != null) {
            obj = env.findMetaObject(languageInfo, obj);
            return new HeapValue(getDebugger(), getSourceRoot(), obj);
        } else {
            return null;
        }
    }

    /**
     * Get a source location where this value is declared, if any.
     *
     * @return a source location of the object, or <code>null</code>
     * @since 0.22
     */
    public final SourceSection getSourceLocation() {
        Object obj = get();
        if (obj == null) {
            return null;
        }
        TruffleInstrument.Env env = getDebugger().getEnv();
        LanguageInfo languageInfo;
        if (preferredLanguage != null) {
            languageInfo = preferredLanguage;
        } else {
            languageInfo = getSourceRoot().getRootNode().getLanguageInfo();
        }
        if (languageInfo != null) {
            return env.findSourceLocation(languageInfo, obj);
        } else {
            return null;
        }
    }

    /**
     * Get the original language that created the value, if any. This method will return
     * <code>null</code> for values representing a primitive value, or objects that are not
     * associated with any language.
     *
     * @return the language, or <code>null</code> when no language can be identified as the creator
     *         of the value.
     * @since 0.27
     */
    public final LanguageInfo getOriginalLanguage() {
        Object obj = get();
        if (obj == null) {
            return null;
        }
        return getDebugger().getEnv().findLanguage(obj);
    }

    /**
     * Returns a debug value that presents itself as seen by the provided language. The language
     * affects the output of {@link #as(java.lang.Class)}, {@link #getMetaObject()} and
     * {@link #getSourceLocation()}. Properties, array elements and other attributes are not
     * affected by a language. The {@link #getOriginalLanguage() original language} of the returned
     * value remains the same as of this value.
     *
     * @param language a language to get the value representation of
     * @return the value as represented in the language
     * @since 0.27
     */
    public final DebugValue asInLanguage(LanguageInfo language) {
        if (preferredLanguage == language) {
            return this;
        }
        if (preferredLanguage == null && getSourceRoot() != null && getSourceRoot().getRootNode().getLanguageInfo() == language) {
            return this;
        }
        return createAsInLanguage(language);
    }

    abstract DebugValue createAsInLanguage(LanguageInfo language);

    abstract Debugger getDebugger();

    abstract RootNode getSourceRoot();

    final LanguageInfo getLanguageInfo() {
        RootNode root = getSourceRoot();
        if (root != null) {
            return root.getLanguageInfo();
        }
        return null;
    }

    /**
     * Returns a string representation of the debug value.
     *
     * @since 0.17
     */
    @Override
    public String toString() {
        return "DebugValue(name=" + getName() + ", value = " + as(String.class) + ")";
    }

    static class HeapValue extends DebugValue {

        // identifies the debugger and engine
        private final Debugger debugger;
        // identifiers the original root this value originates from
        private final RootNode sourceRoot;
        private final Object value;

        HeapValue(Debugger debugger, RootNode root, Object value) {
            this(debugger, root, null, value);
        }

        private HeapValue(Debugger debugger, RootNode root, LanguageInfo preferredLanguage, Object value) {
            super(preferredLanguage);
            this.debugger = debugger;
            this.sourceRoot = root;
            this.value = value;
        }

        @Override
        public <T> T as(Class<T> clazz) {
            if (!isReadable()) {
                throw new IllegalStateException("Value is not readable");
            }
            if (clazz == String.class) {
                Object val = get();
                LanguageInfo languageInfo = null;
                if (preferredLanguage != null) {
                    languageInfo = preferredLanguage;
                } else if (sourceRoot != null) {
                    languageInfo = sourceRoot.getRootNode().getLanguageInfo();
                }
                String stringValue;
                if (languageInfo == null) {
                    stringValue = val.toString();
                } else {
                    stringValue = debugger.getEnv().toString(languageInfo, val);
                }
                return clazz.cast(stringValue);
            }
            throw new UnsupportedOperationException();
        }

        @Override
        Object get() {
            return value;
        }

        @Override
        public void set(DebugValue expression) {
            throw new IllegalStateException("Value is not writable");
        }

        @Override
        public String getName() {
            return null;
        }

        @Override
        public boolean isReadable() {
            return true;
        }

        @Override
        public boolean isWritable() {
            return false;
        }

        @Override
        public boolean isInternal() {
            return false;
        }

        @Override
        DebugValue createAsInLanguage(LanguageInfo language) {
            return new HeapValue(debugger, sourceRoot, language, value);
        }

        @Override
        Debugger getDebugger() {
            return debugger;
        }

        @Override
        RootNode getSourceRoot() {
            return sourceRoot;
        }

    }

    static final class PropertyValue extends HeapValue {

        private final int keyInfo;
        private final Map.Entry<Object, Object> property;
        private final DebugScope scope;

        PropertyValue(Debugger debugger, RootNode root, TruffleObject object, Map.Entry<Object, Object> property, DebugScope scope) {
            this(debugger, root, null, ForeignAccess.sendKeyInfo(Message.KEY_INFO.createNode(), object, property.getKey()), property, scope);
        }

        private PropertyValue(Debugger debugger, RootNode root, LanguageInfo preferredLanguage, int keyInfo, Map.Entry<Object, Object> property, DebugScope scope) {
            super(debugger, root, preferredLanguage, null);
            this.keyInfo = keyInfo;
            this.property = property;
            this.scope = scope;
        }

        @Override
        Object get() {
            checkValid();
            return property.getValue();
        }

        @Override
        public String getName() {
            checkValid();
            String name;
            Object propertyKey = property.getKey();
            if (propertyKey instanceof String) {
                name = (String) propertyKey;
            } else {
                LanguageInfo languageInfo;
                if (preferredLanguage != null) {
                    languageInfo = preferredLanguage;
                } else {
                    languageInfo = getSourceRoot().getRootNode().getLanguageInfo();
                }
                if (languageInfo != null) {
                    name = getDebugger().getEnv().toString(languageInfo, propertyKey);
                } else {
                    name = Objects.toString(propertyKey);
                }
            }
            return name;
        }

        @Override
        public boolean isReadable() {
            checkValid();
            return KeyInfo.isReadable(keyInfo);
        }

        @Override
        public boolean isWritable() {
            checkValid();
            return KeyInfo.isWritable(keyInfo);
        }

        @Override
        public boolean isInternal() {
            checkValid();
            return KeyInfo.isInternal(keyInfo);
        }

        @Override
        public DebugScope getScope() {
            checkValid();
            return scope;
        }

        @Override
        public void set(DebugValue value) {
            checkValid();
            property.setValue(value.get());
        }

        @Override
        DebugValue createAsInLanguage(LanguageInfo language) {
            return new PropertyValue(getDebugger(), getSourceRoot(), language, keyInfo, property, scope);
        }

        private void checkValid() {
            if (scope != null) {
                scope.verifyValidState();
            }
        }
    }

    static final class PropertyNamedValue extends HeapValue {

        private final int keyInfo;
        private final Map<Object, Object> map;
        private final String name;
        private final DebugScope scope;

        PropertyNamedValue(Debugger debugger, RootNode root, TruffleObject object,
                        Map<Object, Object> map, String name, DebugScope scope) {
            this(debugger, root, null, ForeignAccess.sendKeyInfo(Message.KEY_INFO.createNode(), object, name), map, name, scope);
        }

        private PropertyNamedValue(Debugger debugger, RootNode root, LanguageInfo preferredLanguage,
                        int keyInfo, Map<Object, Object> map, String name, DebugScope scope) {
            super(debugger, root, preferredLanguage, null);
            this.keyInfo = keyInfo;
            this.map = map;
            this.name = name;
            this.scope = scope;
        }

        @Override
        public String getName() {
            checkValid();
            return name;
        }

        @Override
        Object get() {
            checkValid();
            return map.get(name);
        }

        @Override
        public DebugScope getScope() {
            checkValid();
            return scope;
        }

        @Override
        public boolean isReadable() {
            checkValid();
            return KeyInfo.isReadable(keyInfo);
        }

        @Override
        public boolean isWritable() {
            checkValid();
            return KeyInfo.isWritable(keyInfo);
        }

        @Override
        public boolean isInternal() {
            checkValid();
            return KeyInfo.isInternal(keyInfo);
        }

        @Override
        public void set(DebugValue value) {
            checkValid();
            map.put(name, value.get());
        }

        @Override
        DebugValue createAsInLanguage(LanguageInfo language) {
            return new PropertyNamedValue(getDebugger(), getSourceRoot(), language, keyInfo, map, name, scope);
        }

        private void checkValid() {
            if (scope != null) {
                scope.verifyValidState();
            }
        }

    }

}
