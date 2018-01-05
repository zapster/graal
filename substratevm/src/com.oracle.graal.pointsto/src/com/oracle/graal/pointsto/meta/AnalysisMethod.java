/*
 * Copyright (c) 2012, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
package com.oracle.graal.pointsto.meta;

import static jdk.vm.ci.common.JVMCIError.shouldNotReachHere;
import static jdk.vm.ci.common.JVMCIError.unimplemented;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.java.BytecodeParser.BytecodeParserError;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.graphbuilderconf.InvocationPlugin;

import com.oracle.graal.pointsto.api.PointstoOptions;
import com.oracle.graal.pointsto.constraints.UnsupportedFeatureException;
import com.oracle.graal.pointsto.flow.InvokeTypeFlow;
import com.oracle.graal.pointsto.flow.MethodTypeFlow;
import com.oracle.graal.pointsto.infrastructure.GraphProvider;
import com.oracle.graal.pointsto.infrastructure.WrappedJavaMethod;
import com.oracle.graal.pointsto.infrastructure.WrappedSignature;
import com.oracle.graal.pointsto.results.StaticAnalysisResults;

import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.ConstantPool;
import jdk.vm.ci.meta.ExceptionHandler;
import jdk.vm.ci.meta.LineNumberTable;
import jdk.vm.ci.meta.Local;
import jdk.vm.ci.meta.LocalVariableTable;
import jdk.vm.ci.meta.ProfilingInfo;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.SpeculationLog;

public class AnalysisMethod implements WrappedJavaMethod, GraphProvider {

    private final AnalysisUniverse universe;
    public final ResolvedJavaMethod wrapped;

    private final int id;
    private final ExceptionHandler[] exceptionHandlers;
    private final LocalVariableTable localVariableTable;
    private MethodTypeFlow typeFlow;

    private boolean isRootMethod;
    private boolean isIntrinsicMethod;
    private Object entryPointData;
    private boolean isInvoked;
    private boolean isImplementationInvoked;

    /**
     * All concrete methods that can actually be called when calling this method. This includes all
     * overridden methods in subclasses, as well as this method if it is non-abstract.
     */
    protected AnalysisMethod[] implementations;

    private ConcurrentMap<InvokeTypeFlow, Object> invokedBy;
    private ConcurrentMap<InvokeTypeFlow, Object> implementationInvokedBy;

    public AnalysisMethod(AnalysisUniverse universe, ResolvedJavaMethod wrapped) {
        this.universe = universe;
        this.wrapped = wrapped;
        this.id = universe.nextMethodId.getAndIncrement();

        if (PointstoOptions.TrackAccessChain.getValue(universe.getHostVM().options())) {
            startTrackInvocations();
        }

        ExceptionHandler[] original = wrapped.getExceptionHandlers();
        exceptionHandlers = new ExceptionHandler[original.length];
        for (int i = 0; i < original.length; i++) {
            ExceptionHandler h = original[i];
            AnalysisType catchType = h.getCatchType() == null ? null : universe.lookup(h.getCatchType().resolve(wrapped.getDeclaringClass()));
            exceptionHandlers[i] = new ExceptionHandler(h.getStartBCI(), h.getEndBCI(), h.getHandlerBCI(), h.catchTypeCPI(), catchType);
        }

        LocalVariableTable newLocalVariableTable = null;
        if (wrapped.getLocalVariableTable() != null) {
            try {
                Local[] origLocals = wrapped.getLocalVariableTable().getLocals();
                Local[] newLocals = new Local[origLocals.length];
                ResolvedJavaType accessingClass = getDeclaringClass().getWrapped();
                for (int i = 0; i < newLocals.length; ++i) {
                    Local origLocal = origLocals[i];
                    ResolvedJavaType origLocalType = origLocal.getType() instanceof ResolvedJavaType ? (ResolvedJavaType) origLocal.getType() : origLocal.getType().resolve(accessingClass);
                    AnalysisType type = universe.lookup(origLocalType);
                    newLocals[i] = new Local(origLocal.getName(), type, origLocal.getStartBCI(), origLocal.getEndBCI(), origLocal.getSlot());
                }
                newLocalVariableTable = new LocalVariableTable(newLocals);
            } catch (LinkageError | UnsupportedFeatureException | BytecodeParserError e) {
                newLocalVariableTable = null;
            }

        }
        localVariableTable = newLocalVariableTable;

        typeFlow = new MethodTypeFlow(universe.getHostVM().options(), this);

        if (getName().startsWith("$SWITCH_TABLE$")) {
            /*
             * The Eclipse Java compiler generates methods that lazily initializes tables for Enum
             * switches. The first invocation fills the table, subsequent invocations reuse the
             * table. We call the method here, so that the table gets built. This ensures that Enum
             * switches are allocation-free at run time.
             */
            assert Modifier.isStatic(getModifiers());
            assert getSignature().getParameterCount(false) == 0;
            try {
                /*
                 * Backdoor into the HotSpot metadata implementation, since there is no official way
                 * to invoke a Graal method.
                 */
                Method toJavaMethod = wrapped.getClass().getDeclaredMethod("toJava");
                toJavaMethod.setAccessible(true);
                Method switchTableMethod = (Method) toJavaMethod.invoke(wrapped);
                switchTableMethod.setAccessible(true);
                switchTableMethod.invoke(null);
            } catch (Throwable ex) {
                throw GraalError.shouldNotReachHere(ex);
            }
        }
    }

    public void cleanupAfterAnalysis() {
        typeFlow = null;
        invokedBy = null;
        implementationInvokedBy = null;
    }

    private void startTrackInvocations() {
        if (invokedBy == null) {
            invokedBy = new ConcurrentHashMap<>();
        }
        if (implementationInvokedBy == null) {
            implementationInvokedBy = new ConcurrentHashMap<>();
        }
    }

    public int getId() {
        return id;
    }

    public MethodTypeFlow getTypeFlow() {
        return typeFlow;
    }

    /**
     * Registers this method as intrinsified to Graal nodes via a {@link InvocationPlugin graph
     * builder plugin}. Such a method is treated similar to an invoked method. For example, method
     * resolution must be able to find the method (otherwise the intrinsification would not work).
     */
    public void registerAsIntrinsicMethod() {
        isIntrinsicMethod = true;
    }

    public void registerAsEntryPoint(Object newEntryPointData) {
        assert newEntryPointData != null;
        if (entryPointData != null && !entryPointData.equals(newEntryPointData)) {
            throw new UnsupportedFeatureException("Method is registered as entry point with conflicting entry point data: " + entryPointData + ", " + newEntryPointData);
        }
        entryPointData = newEntryPointData;
        /* We need that to check that entry points are not invoked from other Java methods. */
        startTrackInvocations();
    }

    public void registerAsInvoked(InvokeTypeFlow invoke) {
        isInvoked = true;
        if (invokedBy != null && invoke != null) {
            invokedBy.put(invoke, Boolean.TRUE);
        }
    }

    public void registerAsImplementationInvoked(InvokeTypeFlow invoke) {
        assert !Modifier.isAbstract(getModifiers());
        isImplementationInvoked = true;
        if (implementationInvokedBy != null && invoke != null) {
            implementationInvokedBy.put(invoke, Boolean.TRUE);
        }
    }

    public List<AnalysisMethod> getJavaInvocations() {
        List<AnalysisMethod> result = new ArrayList<>();
        for (InvokeTypeFlow invoke : implementationInvokedBy.keySet()) {
            result.add((AnalysisMethod) invoke.getSource().graph().method());
        }
        return result;
    }

    public boolean isEntryPoint() {
        return entryPointData != null;
    }

    public Object getEntryPointData() {
        return entryPointData;
    }

    public boolean isIntrinsicMethod() {
        return isIntrinsicMethod;
    }

    public void registerAsRootMethod() {
        isRootMethod = true;
    }

    public boolean isRootMethod() {
        return isRootMethod;
    }

    public boolean isSimplyInvoked() {
        return isInvoked;
    }

    public boolean isSimplyImplementationInvoked() {
        return isImplementationInvoked;
    }

    /**
     * Returns true if this method is ever used as the target of a call site.
     */
    public boolean isInvoked() {
        return isIntrinsicMethod || isEntryPoint() || isInvoked;
    }

    /**
     * Returns true if the method body can ever be executed.
     */
    public boolean isImplementationInvoked() {
        return !Modifier.isAbstract(getModifiers()) && (isIntrinsicMethod || isEntryPoint() || isImplementationInvoked);
    }

    @Override
    public ResolvedJavaMethod getWrapped() {
        return wrapped;
    }

    @Override
    public String getName() {
        return wrapped.getName();
    }

    @Override
    public WrappedSignature getSignature() {
        return universe.lookup(wrapped.getSignature(), getDeclaringClass());
    }

    @Override
    public StructuredGraph buildGraph(DebugContext debug, ResolvedJavaMethod method, HostedProviders providers, Purpose purpose) {
        if (wrapped instanceof GraphProvider) {
            return ((GraphProvider) wrapped).buildGraph(debug, method, providers, purpose);
        }
        return null;
    }

    @Override
    public byte[] getCode() {
        return wrapped.getCode();
    }

    @Override
    public int getCodeSize() {
        return wrapped.getCodeSize();
    }

    @Override
    public AnalysisType getDeclaringClass() {
        return universe.lookup(wrapped.getDeclaringClass());
    }

    @Override
    public int getMaxLocals() {
        if (isNative()) {
            return getSignature().getParameterCount(!Modifier.isStatic(getModifiers())) * 2;
        }
        return wrapped.getMaxLocals();
    }

    @Override
    public int getMaxStackSize() {
        if (isNative()) {
            // At most we have a double-slot return value.
            return 2;
        }
        return wrapped.getMaxStackSize();
    }

    @Override
    public Parameter[] getParameters() {
        return wrapped.getParameters();
    }

    @Override
    public int getModifiers() {
        return wrapped.getModifiers();
    }

    @Override
    public boolean isSynthetic() {
        return wrapped.isSynthetic();
    }

    @Override
    public boolean isVarArgs() {
        throw unimplemented();
    }

    @Override
    public boolean isBridge() {
        return wrapped.isBridge();
    }

    @Override
    public boolean isClassInitializer() {
        return wrapped.isClassInitializer();
    }

    @Override
    public boolean isConstructor() {
        return wrapped.isConstructor();
    }

    @Override
    public boolean canBeStaticallyBound() {
        return wrapped.canBeStaticallyBound();
    }

    public AnalysisMethod[] getImplementations() {
        assert universe.analysisDataValid;
        if (implementations == null) {
            return new AnalysisMethod[0];
        }
        return implementations;
    }

    @Override
    public ExceptionHandler[] getExceptionHandlers() {
        return exceptionHandlers;
    }

    @Override
    public StackTraceElement asStackTraceElement(int bci) {
        return wrapped.asStackTraceElement(bci);
    }

    @Override
    public ProfilingInfo getProfilingInfo(boolean includeNormal, boolean includeOSR) {
        /*
         * This is also the profiling information used when parsing methods for static analysis, so
         * it needs to be conservative.
         */
        return StaticAnalysisResults.NO_RESULTS;
    }

    @Override
    public ConstantPool getConstantPool() {
        return universe.lookup(wrapped.getConstantPool(), getDeclaringClass());
    }

    @Override
    public Annotation[] getAnnotations() {
        return wrapped.getAnnotations();
    }

    @Override
    public Annotation[] getDeclaredAnnotations() {
        return wrapped.getDeclaredAnnotations();
    }

    @Override
    public <T extends Annotation> T getAnnotation(Class<T> annotationClass) {
        return wrapped.getAnnotation(annotationClass);
    }

    @Override
    public Annotation[][] getParameterAnnotations() {
        return wrapped.getParameterAnnotations();
    }

    @Override
    public Type[] getGenericParameterTypes() {
        return wrapped.getGenericParameterTypes();
    }

    @Override
    public boolean canBeInlined() {
        return true;
    }

    @Override
    public boolean hasNeverInlineDirective() {
        return wrapped.hasNeverInlineDirective();
    }

    @Override
    public boolean shouldBeInlined() {
        throw unimplemented();
    }

    @Override
    public LineNumberTable getLineNumberTable() {
        return wrapped.getLineNumberTable();
    }

    @Override
    public String toString() {
        return "AnalysisMethod<" + format("%h.%n") + " -> " + wrapped.toString() + ">";
    }

    @Override
    public LocalVariableTable getLocalVariableTable() {
        return localVariableTable;
    }

    @Override
    public void reprofile() {
        throw unimplemented();
    }

    @Override
    public Constant getEncoding() {
        throw unimplemented();
    }

    @Override
    public boolean isInVirtualMethodTable(ResolvedJavaType resolved) {
        return false;
    }

    @Override
    public boolean isDefault() {
        throw unimplemented();
    }

    @Override
    public SpeculationLog getSpeculationLog() {
        throw shouldNotReachHere();
    }

    @Override
    public int hashCode() {
        return id;
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj;
    }
}
