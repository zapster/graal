/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.LinkedHashSet;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.TruffleOptions;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.ForeignAccess;
import com.oracle.truffle.api.interop.InteropException;
import com.oracle.truffle.api.interop.Message;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.interop.java.ObjectProxyHandlerFactory.InvokeAndReadExecNodeGen;
import com.oracle.truffle.api.interop.java.ObjectProxyHandlerFactory.MethodNodeGen;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;

final class JavaInteropReflect {
    static final Object[] EMPTY = {};

    private JavaInteropReflect() {
    }

    @CompilerDirectives.TruffleBoundary
    static Class<?> findInnerClass(Class<?> clazz, String name) {
        if (Modifier.isPublic(clazz.getModifiers())) {
            for (Class<?> t : clazz.getClasses()) {
                // no support for non-static type members now
                if (isStaticTypeOrInterface(t) && t.getSimpleName().equals(name)) {
                    return t;
                }
            }
        }
        return null;
    }

    static boolean isField(JavaObject object, String name) {
        JavaClassDesc classDesc = JavaClassDesc.forClass(object.clazz);
        final boolean onlyStatic = object.isClass();
        return classDesc.lookupField(name, onlyStatic) != null;
    }

    static boolean isMethod(JavaObject object, String name) {
        JavaClassDesc classDesc = JavaClassDesc.forClass(object.clazz);
        final boolean onlyStatic = object.isClass();
        return classDesc.lookupMethod(name, onlyStatic) != null;
    }

    static boolean isInternalMethod(JavaObject object, String name) {
        JavaClassDesc classDesc = JavaClassDesc.forClass(object.clazz);
        final boolean onlyStatic = object.isClass();
        JavaMethodDesc method = classDesc.lookupMethod(name, onlyStatic);
        return method != null && method.isInternal();
    }

    static boolean isMemberType(JavaObject object, String name) {
        final boolean onlyStatic = object.isClass();
        if (!onlyStatic) {
            // no support for non-static members now
            return false;
        }
        Class<?> clazz = object.clazz;
        if (Modifier.isPublic(clazz.getModifiers())) {
            for (Class<?> t : clazz.getClasses()) {
                if (isStaticTypeOrInterface(t) && t.getSimpleName().equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean isJNIName(String name) {
        return name.contains("__");
    }

    @CompilerDirectives.TruffleBoundary
    static boolean isApplicableByArity(JavaMethodDesc method, int nArgs) {
        if (method instanceof SingleMethodDesc) {
            return nArgs == ((SingleMethodDesc) method).getParameterCount() ||
                            ((SingleMethodDesc) method).isVarArgs() && nArgs >= ((SingleMethodDesc) method).getParameterCount() - 1;
        } else {
            SingleMethodDesc[] overloads = ((OverloadedMethodDesc) method).getOverloads();
            for (SingleMethodDesc overload : overloads) {
                if (isApplicableByArity(overload, nArgs)) {
                    return true;
                }
            }
            return false;
        }
    }

    @CompilerDirectives.TruffleBoundary
    static JavaMethodDesc findMethod(JavaObject object, String name) {
        if (TruffleOptions.AOT) {
            return null;
        }

        JavaClassDesc classDesc = JavaClassDesc.forClass(object.clazz);
        JavaMethodDesc foundMethod = classDesc.lookupMethod(name, object.isClass());
        if (foundMethod == null && isJNIName(name)) {
            foundMethod = classDesc.lookupMethodByJNIName(name, object.isClass());
        }
        return foundMethod;
    }

    @CompilerDirectives.TruffleBoundary
    static JavaFieldDesc findField(JavaObject receiver, String name) {
        JavaClassDesc classDesc = JavaClassDesc.forClass(receiver.clazz);
        final boolean onlyStatic = receiver.isClass();
        return classDesc.lookupField(name, onlyStatic);
    }

    @CompilerDirectives.TruffleBoundary
    static <T> T asJavaFunction(Class<T> functionalType, TruffleObject function, Object languageContext) {
        assert JavaInterop.isJavaFunctionInterface(functionalType);
        final FunctionProxyHandler handler = new FunctionProxyHandler(function, languageContext);
        Object obj = Proxy.newProxyInstance(functionalType.getClassLoader(), new Class<?>[]{functionalType}, handler);
        return functionalType.cast(obj);
    }

    @CompilerDirectives.TruffleBoundary
    static TruffleObject asTruffleViaReflection(Object obj, Object languageContext) {
        if (Proxy.isProxyClass(obj.getClass())) {
            InvocationHandler h = Proxy.getInvocationHandler(obj);
            if (h instanceof ObjectProxyHandler) {
                return ((ObjectProxyHandler) h).obj;
            }
        }
        return new JavaObject(obj, obj.getClass(), languageContext);
    }

    static Object newProxyInstance(Class<?> clazz, TruffleObject obj) throws IllegalArgumentException {
        return Proxy.newProxyInstance(clazz.getClassLoader(), new Class<?>[]{clazz}, new ObjectProxyHandler(obj));
    }

    static boolean isStaticTypeOrInterface(Class<?> t) {
        // anonymous classes are private, they should be eliminated elsewhere
        return Modifier.isPublic(t.getModifiers()) && (t.isInterface() || t.isEnum() || Modifier.isStatic(t.getModifiers()));
    }

    @CompilerDirectives.TruffleBoundary
    static String[] findUniquePublicMemberNames(Class<?> clazz, boolean onlyInstance, boolean includeInternal) throws SecurityException {
        JavaClassDesc classDesc = JavaClassDesc.forClass(clazz);
        Collection<String> names = new LinkedHashSet<>();
        names.addAll(classDesc.getFieldNames(!onlyInstance));
        names.addAll(classDesc.getMethodNames(!onlyInstance, includeInternal));
        if (!onlyInstance) {
            if (Modifier.isPublic(clazz.getModifiers())) {
                // no support for non-static member types now
                for (Class<?> t : clazz.getClasses()) {
                    if (!isStaticTypeOrInterface(t)) {
                        continue;
                    }
                    names.add(t.getSimpleName());
                }
            }
        }
        return names.toArray(new String[0]);
    }

    @SuppressWarnings({"unchecked"})
    static <E extends Throwable> RuntimeException rethrow(Throwable ex) throws E {
        throw (E) ex;
    }

    static Class<?> getMethodReturnType(Method method) {
        if (method == null || method.getReturnType() == void.class) {
            return Object.class;
        }
        return method.getReturnType();
    }

    static Type getMethodGenericReturnType(Method method) {
        if (method == null || method.getReturnType() == void.class) {
            return Object.class;
        }
        return method.getGenericReturnType();
    }

    static String jniName(Method m) {
        StringBuilder sb = new StringBuilder();
        noUnderscore(sb, m.getName()).append("__");
        appendType(sb, m.getReturnType());
        Class<?>[] arr = m.getParameterTypes();
        for (int i = 0; i < arr.length; i++) {
            appendType(sb, arr[i]);
        }
        return sb.toString();
    }

    private static StringBuilder noUnderscore(StringBuilder sb, String name) {
        return sb.append(name.replace("_", "_1").replace('.', '_'));
    }

    private static void appendType(StringBuilder sb, Class<?> type) {
        if (type == Integer.TYPE) {
            sb.append('I');
            return;
        }
        if (type == Long.TYPE) {
            sb.append('J');
            return;
        }
        if (type == Double.TYPE) {
            sb.append('D');
            return;
        }
        if (type == Float.TYPE) {
            sb.append('F');
            return;
        }
        if (type == Byte.TYPE) {
            sb.append('B');
            return;
        }
        if (type == Boolean.TYPE) {
            sb.append('Z');
            return;
        }
        if (type == Short.TYPE) {
            sb.append('S');
            return;
        }
        if (type == Void.TYPE) {
            sb.append('V');
            return;
        }
        if (type == Character.TYPE) {
            sb.append('C');
            return;
        }
        if (type.isArray()) {
            sb.append("_3");
            appendType(sb, type.getComponentType());
            return;
        }
        noUnderscore(sb.append('L'), type.getName());
        sb.append("_2");
    }
}

final class FunctionProxyHandler implements InvocationHandler {
    private final TruffleObject symbol;
    private CallTarget target;
    private final Object languageContext;

    FunctionProxyHandler(TruffleObject obj, Object languageContext) {
        this.symbol = obj;
        this.languageContext = languageContext;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
        Object ret;
        if (method.isVarArgs()) {
            if (arguments.length == 1) {
                ret = call((Object[]) arguments[0], method);
            } else {
                final int allButOne = arguments.length - 1;
                Object[] last = (Object[]) arguments[allButOne];
                Object[] merge = new Object[allButOne + last.length];
                System.arraycopy(arguments, 0, merge, 0, allButOne);
                System.arraycopy(last, 0, merge, allButOne, last.length);
                ret = call(merge, method);
            }
        } else {
            ret = call(arguments, method);
        }
        return toJava(ret, method, languageContext);
    }

    private Object call(Object[] arguments, Method method) {
        CompilerAsserts.neverPartOfCompilation();
        Object[] args = arguments == null ? JavaInteropReflect.EMPTY : arguments;
        if (target == null) {
            target = JavaInterop.lookupOrRegisterComputation(symbol, null, JavaInteropReflect.class);
            if (target == null) {
                Node executeMain = Message.createExecute(args.length).createNode();
                RootNode symbolNode = new ToJavaNode.TemporaryRoot(executeMain);
                target = JavaInterop.lookupOrRegisterComputation(symbol, symbolNode, JavaInteropReflect.class);
            }
        }
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg instanceof TruffleObject) {
                continue;
            } else if (JavaInterop.isPrimitive(arg)) {
                continue;
            } else {
                arguments[i] = JavaInterop.toGuestValue(arg, languageContext);
            }
        }
        return target.call(symbol, args, JavaInteropReflect.getMethodReturnType(method), JavaInteropReflect.getMethodGenericReturnType(method), languageContext);
    }

    private static Object toJava(Object ret, Method method, Object languageContext) {
        return ToJavaNode.toJava(ret, JavaInteropReflect.getMethodReturnType(method), JavaInteropReflect.getMethodGenericReturnType(method), languageContext);
    }
}

final class ObjectProxyHandler implements InvocationHandler {
    final TruffleObject obj;

    ObjectProxyHandler(TruffleObject obj) {
        this.obj = obj;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
        CompilerAsserts.neverPartOfCompilation();

        Object[] args = arguments == null ? JavaInteropReflect.EMPTY : arguments;
        for (int i = 0; i < args.length; i++) {
            args[i] = JavaInterop.asTruffleValue(args[i]);
        }

        if (Object.class == method.getDeclaringClass()) {
            return method.invoke(obj, args);
        }

        CallTarget call = JavaInterop.lookupOrRegisterComputation(obj, null, method);
        if (call == null) {
            Message message = findMessage(method, method.getAnnotation(MethodMessage.class), args.length);
            MethodNode methodNode = MethodNodeGen.create(method.getName(), message, JavaInteropReflect.getMethodReturnType(method), JavaInteropReflect.getMethodGenericReturnType(method));
            call = JavaInterop.lookupOrRegisterComputation(obj, methodNode, method);
        }

        return call.call(obj, args);
    }

    private static Message findMessage(Method method, MethodMessage mm, int arity) {
        CompilerAsserts.neverPartOfCompilation();
        if (mm == null) {
            return null;
        }
        Message message = Message.valueOf(mm.message());
        if (message == Message.WRITE && arity != 1) {
            throw new IllegalStateException("Method needs to have a single argument to handle WRITE message " + method);
        }
        return message;
    }

    abstract static class MethodNode extends RootNode {
        private final String name;
        private final Message message;
        private final Class<?> returnType;
        private final Type genericReturnType;
        @Child private ToJavaNode toJavaNode;
        @Child private Node node;

        MethodNode(String name, Message message, Class<?> returnType, Type genericReturnType) {
            super(null);
            this.name = name;
            this.message = message;
            this.returnType = returnType;
            this.genericReturnType = genericReturnType;
            this.toJavaNode = ToJavaNode.create();
        }

        @Override
        public final Object execute(VirtualFrame frame) {
            try {
                TruffleObject receiver = (TruffleObject) frame.getArguments()[0];
                Object[] params = (Object[]) frame.getArguments()[1];
                Object res = executeImpl(receiver, params);
                if (!returnType.isInterface()) {
                    res = JavaInterop.findOriginalObject(res);
                }
                return toJavaNode.execute(res, returnType, genericReturnType);
            } catch (InteropException ex) {
                throw ex.raise();
            }
        }

        private Object handleMessage(Node messageNode, TruffleObject obj, Object[] args) throws InteropException {
            if (message == Message.WRITE) {
                ForeignAccess.sendWrite(messageNode, obj, name, args[0]);
                return null;
            }
            if (message == Message.HAS_SIZE || message == Message.IS_BOXED || message == Message.IS_EXECUTABLE || message == Message.IS_NULL || message == Message.GET_SIZE) {
                return ForeignAccess.send(messageNode, obj);
            }

            if (message == Message.KEY_INFO) {
                return ForeignAccess.sendKeyInfo(messageNode, obj, name);
            }

            if (message == Message.READ) {
                return ForeignAccess.sendRead(messageNode, obj, name);
            }

            if (message == Message.UNBOX) {
                return ForeignAccess.sendUnbox(messageNode, obj);
            }

            if (Message.createExecute(0).equals(message)) {
                return ForeignAccess.sendExecute(messageNode, obj, args);
            }

            if (Message.createInvoke(0).equals(message)) {
                return ForeignAccess.sendInvoke(messageNode, obj, name, args);
            }

            if (Message.createNew(0).equals(message)) {
                return ForeignAccess.sendNew(messageNode, obj, args);
            }

            if (message == null) {
                return ((InvokeAndReadExecNode) messageNode).executeDispatch(obj, name, args);
            }
            CompilerDirectives.transferToInterpreter();
            throw UnsupportedMessageException.raise(message);
        }

        Node node(Object[] args) {
            if (node == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                this.node = insert(createNode(args));
            }
            return node;
        }

        Node createNode(Object[] args) {
            if (message == null) {
                return InvokeAndReadExecNodeGen.create(returnType, args.length);
            }
            return message.createNode();
        }

        protected abstract Object executeImpl(TruffleObject receiver, Object[] arguments) throws InteropException;

        @SuppressWarnings("unused")
        @Specialization(guards = "acceptCached(receiver, foreignAccess, canHandleCall)", limit = "8")
        protected Object doCached(TruffleObject receiver, Object[] arguments,
                        @Cached("receiver.getForeignAccess()") ForeignAccess foreignAccess,
                        @Cached("createInlinedCallNode(createCanHandleTarget(foreignAccess))") DirectCallNode canHandleCall,
                        @Cached("createNode(arguments)") Node messageNode) {
            try {
                return handleMessage(messageNode, receiver, arguments);
            } catch (InteropException ex) {
                throw ex.raise();
            }
        }

        protected static boolean acceptCached(TruffleObject receiver, ForeignAccess foreignAccess, DirectCallNode canHandleCall) {
            if (canHandleCall != null) {
                return (boolean) canHandleCall.call(new Object[]{receiver});
            } else if (foreignAccess != null) {
                return JavaInterop.ACCESSOR.interop().canHandle(foreignAccess, receiver);
            } else {
                return false;
            }
        }

        static DirectCallNode createInlinedCallNode(CallTarget target) {
            if (target == null) {
                return null;
            }
            DirectCallNode callNode = DirectCallNode.create(target);
            callNode.forceInlining();
            return callNode;
        }

        @Specialization
        Object doGeneric(TruffleObject receiver, Object[] arguments) {
            try {
                return handleMessage(node(arguments), receiver, arguments);
            } catch (InteropException ex) {
                throw ex.raise();
            }
        }

        @CompilerDirectives.TruffleBoundary
        CallTarget createCanHandleTarget(ForeignAccess access) {
            return JavaInterop.ACCESSOR.interop().canHandleTarget(access);
        }

    }

    abstract static class InvokeAndReadExecNode extends Node {
        private final Class<?> returnType;
        @Child private Node invokeNode;
        @Child private Node isExecNode;
        @Child private ToPrimitiveNode primitive;
        @Child private Node readNode;
        @Child private Node execNode;

        InvokeAndReadExecNode(Class<?> returnType, int arity) {
            this.returnType = returnType;
            this.invokeNode = Message.createInvoke(arity).createNode();
        }

        abstract Object executeDispatch(TruffleObject obj, String name, Object[] args);

        @Specialization(rewriteOn = InteropException.class)
        Object doInvoke(TruffleObject obj, String name, Object[] args) throws InteropException {
            return ForeignAccess.sendInvoke(invokeNode, obj, name, args);
        }

        @Specialization(rewriteOn = UnsupportedMessageException.class)
        Object doReadExec(TruffleObject obj, String name, Object[] args) throws UnsupportedMessageException {
            try {
                if (readNode == null || primitive == null || isExecNode == null) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    readNode = insert(Message.READ.createNode());
                    primitive = insert(ToPrimitiveNode.create());
                    isExecNode = insert(Message.IS_EXECUTABLE.createNode());
                }
                Object val = ForeignAccess.sendRead(readNode, obj, name);
                Object primitiveVal = primitive.toPrimitive(val, returnType);
                if (primitiveVal != null) {
                    return primitiveVal;
                }
                TruffleObject attr = (TruffleObject) val;
                if (!ForeignAccess.sendIsExecutable(isExecNode, attr)) {
                    if (args.length == 0) {
                        return attr;
                    }
                    CompilerDirectives.transferToInterpreter();
                    throw ArityException.raise(0, args.length);
                }
                if (execNode == null) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    execNode = insert(Message.createExecute(args.length).createNode());
                }
                return ForeignAccess.sendExecute(execNode, attr, args);
            } catch (ArityException | UnsupportedTypeException | UnknownIdentifierException ex) {
                throw ex.raise();
            }
        }

        @Specialization
        @CompilerDirectives.TruffleBoundary
        Object doBoth(TruffleObject obj, String name, Object[] args) {
            try {
                return doInvoke(obj, name, args);
            } catch (InteropException retry) {
                try {
                    return doReadExec(obj, name, args);
                } catch (UnsupportedMessageException ex) {
                    throw ex.raise();
                }
            }
        }
    }
}
