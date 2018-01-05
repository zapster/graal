/*
 * Copyright (c) 2013, 2017, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.compiler.truffle;

import static org.graalvm.compiler.serviceprovider.JDK9Method.Java8OrEarlier;
import static org.graalvm.compiler.truffle.TruffleCompilerOptions.TruffleCompilationExceptionsAreThrown;
import static org.graalvm.compiler.truffle.TruffleCompilerOptions.TruffleCompileOnly;
import static org.graalvm.compiler.truffle.TruffleCompilerOptions.TruffleCompilerThreads;
import static org.graalvm.compiler.truffle.TruffleCompilerOptions.TruffleInstrumentBoundaries;
import static org.graalvm.compiler.truffle.TruffleCompilerOptions.TruffleInstrumentBranches;
import static org.graalvm.compiler.truffle.TruffleCompilerOptions.TruffleProfilingEnabled;
import static org.graalvm.compiler.truffle.TruffleCompilerOptions.TruffleUseFrameWithoutBoxing;
import static org.graalvm.compiler.truffle.TruffleCompilerOptions.getValue;
import static org.graalvm.compiler.truffle.TruffleCompilerOptions.overrideOptions;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.graalvm.compiler.api.runtime.GraalRuntime;
import org.graalvm.compiler.code.CompilationResult;
import org.graalvm.compiler.core.CompilationWrapper;
import org.graalvm.compiler.core.CompilationWrapper.ExceptionAction;
import org.graalvm.compiler.core.CompilerThreadFactory;
import org.graalvm.compiler.core.common.CompilationIdentifier;
import org.graalvm.compiler.core.target.Backend;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.DiagnosticsOutputDirectory;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.debug.TTY;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.options.OptionValues;
import org.graalvm.compiler.serviceprovider.GraalServices;
import org.graalvm.compiler.truffle.TruffleCompilerOptions.TruffleOptionsOverrideScope;
import org.graalvm.compiler.truffle.debug.CompilationStatisticsListener;
import org.graalvm.compiler.truffle.debug.TraceCompilationASTListener;
import org.graalvm.compiler.truffle.debug.TraceCompilationCallTreeListener;
import org.graalvm.compiler.truffle.debug.TraceCompilationFailureListener;
import org.graalvm.compiler.truffle.debug.TraceCompilationListener;
import org.graalvm.compiler.truffle.debug.TraceCompilationPolymorphismListener;
import org.graalvm.compiler.truffle.debug.TraceInliningListener;
import org.graalvm.compiler.truffle.debug.TraceSplittingListener;
import org.graalvm.compiler.truffle.phases.InstrumentPhase;
import org.graalvm.util.CollectionsUtil;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.CompilerOptions;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleOptions;
import com.oracle.truffle.api.TruffleRuntime;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.frame.FrameInstanceVisitor;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.impl.TVMCI;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.LoopNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RepeatingNode;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.object.LayoutFactory;

import jdk.vm.ci.code.BailoutException;
import jdk.vm.ci.code.stack.InspectedFrame;
import jdk.vm.ci.code.stack.InspectedFrameVisitor;
import jdk.vm.ci.code.stack.StackIntrospection;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.SpeculationLog;

public abstract class GraalTruffleRuntime implements TruffleRuntime {

    /**
     * Used only to reset state for native image compilation.
     */
    protected void clearState() {
        assert TruffleOptions.AOT : "Must be called only in AOT mode.";
        callMethods = null;
        truffleCompiler = null;
    }

    protected static class BackgroundCompileQueue {
        private final ExecutorService compileQueue;

        public BackgroundCompileQueue() {
            CompilerThreadFactory factory = new CompilerThreadFactory("TruffleCompilerThread");

            int selectedProcessors = TruffleCompilerOptions.getValue(TruffleCompilerThreads);
            if (selectedProcessors == 0) {
                // No manual selection made, check how many processors are available.
                int availableProcessors = Runtime.getRuntime().availableProcessors();
                if (availableProcessors >= 4) {
                    selectedProcessors = 2;
                }
            }
            selectedProcessors = Math.max(1, selectedProcessors);
            compileQueue = Executors.newFixedThreadPool(selectedProcessors, factory);
        }
    }

    private Object cachedIncludesExcludes;
    private ArrayList<String> includes;
    private ArrayList<String> excludes;

    private final List<GraalTruffleCompilationListener> compilationListeners = new ArrayList<>();
    private final GraalTruffleCompilationListener compilationNotify = new DispatchTruffleCompilationListener();

    protected volatile TruffleCompiler truffleCompiler;
    protected LoopNodeFactory loopNodeFactory;
    protected CallMethods callMethods;

    private final Supplier<GraalRuntime> graalRuntime;
    private final GraalTVMCI tvmci = new GraalTVMCI();

    private volatile GraalTestTVMCI testTvmci;

    /**
     * The instrumentation object is used by the Truffle instrumentation to count executions. The
     * value is lazily initialized the first time it is requested because it depends on the Truffle
     * options, and tests that need the instrumentation table need to override these options after
     * the TruffleRuntime object is created.
     */
    private volatile InstrumentPhase.Instrumentation instrumentation;

    /**
     * Utility method that casts the singleton {@link TruffleRuntime}.
     */
    public static GraalTruffleRuntime getRuntime() {
        return (GraalTruffleRuntime) Truffle.getRuntime();
    }

    /**
     * Gets the initial option values for this Graal-based Truffle runtime.
     */
    public abstract OptionValues getInitialOptions();

    /**
     * Opens a debug context for compiling {@code callTarget}. The {@link DebugContext#close()}
     * method should be called on the returned object once the compilation is finished.
     */
    protected abstract DebugContext openDebugContext(OptionValues options, CompilationIdentifier compilationId, OptimizedCallTarget callTarget);

    public GraalTruffleRuntime(Supplier<GraalRuntime> graalRuntime) {
        this.graalRuntime = graalRuntime;

    }

    protected GraalTVMCI getTvmci() {
        return tvmci;
    }

    protected TVMCI.Test<?> getTestTvmci() {
        if (testTvmci == null) {
            synchronized (this) {
                if (testTvmci == null) {
                    testTvmci = new GraalTestTVMCI(this);
                }
            }
        }
        return testTvmci;
    }

    public abstract TruffleCompiler getTruffleCompiler();

    public <T> T getRequiredGraalCapability(Class<T> clazz) {
        T ret = graalRuntime.get().getCapability(clazz);
        if (ret == null) {
            throw new GraalError("The VM does not expose the required Graal capability %s.", clazz.getName());
        }
        return ret;
    }

    private static <T extends PrioritizedServiceProvider> T loadPrioritizedServiceProvider(Class<T> clazz) {
        Iterable<T> providers = GraalServices.load(clazz);
        T bestFactory = null;
        for (T factory : providers) {
            if (bestFactory == null) {
                bestFactory = factory;
            } else if (factory.getPriority() > bestFactory.getPriority()) {
                bestFactory = factory;
            }
        }
        if (bestFactory == null) {
            throw new IllegalStateException("Unable to load a factory for " + clazz.getName());
        }
        return bestFactory;
    }

    public void log(String message) {
        TTY.out().println(message);
    }

    protected void installDefaultListeners() {
        TraceCompilationFailureListener.install(this);
        TraceCompilationListener.install(this);
        TraceCompilationPolymorphismListener.install(this);
        TraceCompilationCallTreeListener.install(this);
        TraceInliningListener.install(this);
        TraceSplittingListener.install(this);
        CompilationStatisticsListener.install(this);
        TraceCompilationASTListener.install(this);
        installShutdownHooks();
        compilationNotify.notifyStartup(this);
    }

    protected void installShutdownHooks() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
    }

    protected void lookupCallMethods(MetaAccessProvider metaAccess) {
        callMethods = CallMethods.lookup(metaAccess);
    }

    /** Accessor for non-public state in {@link FrameDescriptor}. */
    public void markFrameMaterializeCalled(FrameDescriptor descriptor) {
        try {
            getTvmci().markFrameMaterializeCalled(descriptor);
        } catch (Throwable ex) {
            /*
             * Backward compatibility: do nothing on old Truffle version where the field in
             * FrameDescriptor does not exist.
             */
        }
    }

    /** Accessor for non-public state in {@link FrameDescriptor}. */
    public boolean getFrameMaterializeCalled(FrameDescriptor descriptor) {
        try {
            return getTvmci().getFrameMaterializeCalled(descriptor);
        } catch (Throwable ex) {
            /*
             * Backward compatibility: be conservative on old Truffle version where the field in
             * FrameDescriptor does not exist.
             */
            return true;
        }
    }

    @Override
    public LoopNode createLoopNode(RepeatingNode repeatingNode) {
        if (!(repeatingNode instanceof Node)) {
            throw new IllegalArgumentException("Repeating node must be of type Node.");
        }
        return getLoopNodeFactory().create(repeatingNode);
    }

    protected LoopNodeFactory getLoopNodeFactory() {
        if (loopNodeFactory == null) {
            loopNodeFactory = loadPrioritizedServiceProvider(LoopNodeFactory.class);
        }
        return loopNodeFactory;
    }

    @Override
    public DirectCallNode createDirectCallNode(CallTarget target) {
        if (target instanceof OptimizedCallTarget) {
            return new OptimizedDirectCallNode(this, (OptimizedCallTarget) target);
        } else {
            throw new IllegalStateException(String.format("Unexpected call target class %s!", target.getClass()));
        }
    }

    @Override
    public IndirectCallNode createIndirectCallNode() {
        return new OptimizedIndirectCallNode();
    }

    @Override
    public VirtualFrame createVirtualFrame(Object[] arguments, FrameDescriptor frameDescriptor) {
        return OptimizedCallTarget.createFrame(frameDescriptor, arguments);
    }

    @Override
    public MaterializedFrame createMaterializedFrame(Object[] arguments) {
        return createMaterializedFrame(arguments, new FrameDescriptor());
    }

    @Override
    public MaterializedFrame createMaterializedFrame(Object[] arguments, FrameDescriptor frameDescriptor) {
        if (LazyFrameBoxingQuery.useFrameWithoutBoxing) {
            return new FrameWithoutBoxing(frameDescriptor, arguments);
        } else {
            return new FrameWithBoxing(frameDescriptor, arguments);
        }
    }

    @Override
    public CompilerOptions createCompilerOptions() {
        return new GraalCompilerOptions();
    }

    @Override
    public Assumption createAssumption() {
        return createAssumption(null);
    }

    @Override
    public Assumption createAssumption(String name) {
        return new OptimizedAssumption(name);
    }

    public GraalTruffleCompilationListener getCompilationNotify() {
        return compilationNotify;
    }

    @TruffleBoundary
    @Override
    public <T> T iterateFrames(final FrameInstanceVisitor<T> visitor) {
        return iterateImpl(visitor, 0);
    }

    private static final class FrameVisitor<T> implements InspectedFrameVisitor<T> {

        private final FrameInstanceVisitor<T> visitor;
        private final CallMethods methods;

        private int skipFrames;

        private InspectedFrame callNodeFrame;

        FrameVisitor(FrameInstanceVisitor<T> visitor, CallMethods methods, int skip) {
            this.visitor = visitor;
            this.methods = methods;
            this.skipFrames = skip;
        }

        @Override
        public T visitFrame(InspectedFrame frame) {
            if (frame.isMethod(methods.callOSRMethod)) {
                // we ignore OSR frames.
                skipFrames++;
                return null;
            } else if (frame.isMethod(methods.callTargetMethod)) {
                if (skipFrames == 0) {
                    try {
                        return visitor.visitFrame(new GraalFrameInstance(frame, callNodeFrame));
                    } finally {
                        callNodeFrame = null;
                    }
                } else {
                    skipFrames--;
                }
            } else if (frame.isMethod(methods.callNodeMethod)) {
                callNodeFrame = frame;
            }
            return null;
        }
    }

    private <T> T iterateImpl(FrameInstanceVisitor<T> visitor, final int skip) {
        CallMethods methods = getCallMethods();
        FrameVisitor<T> jvmciVisitor = new FrameVisitor<>(visitor, methods, skip);
        return getStackIntrospection().iterateFrames(methods.anyFrameMethod, methods.anyFrameMethod, 0, jvmciVisitor);
    }

    protected abstract StackIntrospection getStackIntrospection();

    @Override
    public FrameInstance getCallerFrame() {
        return iterateImpl(frame -> frame, 1);
    }

    @TruffleBoundary
    @Override
    public FrameInstance getCurrentFrame() {
        return iterateImpl(frame -> frame, 0);
    }

    @Override
    public <T> T getCapability(Class<T> capability) {
        if (capability == TVMCI.class) {
            return capability.cast(tvmci);
        } else if (capability == LayoutFactory.class) {
            return capability.cast(loadObjectLayoutFactory());
        } else if (capability == TVMCI.Test.class) {
            return capability.cast(getTestTvmci());
        }
        try {
            Iterator<T> services = GraalServices.load(capability).iterator();
            if (services.hasNext()) {
                return services.next();
            }
            return null;
        } catch (ServiceConfigurationError e) {
            // Happens on JDK9 when a service type has not been exported to Graal
            // or Graal's module descriptor does not declare a use of capability.
            return null;
        }
    }

    @SuppressFBWarnings(value = "", justification = "Cache that does not need to use equals to compare.")
    final boolean acceptForCompilation(RootNode rootNode) {
        String includesExcludes = getValue(TruffleCompileOnly);
        if (includesExcludes != null) {
            if (cachedIncludesExcludes != includesExcludes) {
                parseCompileOnly();
                this.cachedIncludesExcludes = includesExcludes;
            }

            String name = rootNode.getName();
            boolean included = includes.isEmpty();
            if (name != null) {
                for (int i = 0; !included && i < includes.size(); i++) {
                    if (name.contains(includes.get(i))) {
                        included = true;
                    }
                }
            }
            if (!included) {
                return false;
            }
            if (name != null) {
                for (String exclude : excludes) {
                    if (name.contains(exclude)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    protected void parseCompileOnly() {
        ArrayList<String> includesList = new ArrayList<>();
        ArrayList<String> excludesList = new ArrayList<>();

        String[] items = getValue(TruffleCompileOnly).split(",");
        for (String item : items) {
            if (item.startsWith("~")) {
                excludesList.add(item.substring(1));
            } else {
                includesList.add(item);
            }
        }
        this.includes = includesList;
        this.excludes = excludesList;
    }

    public abstract SpeculationLog createSpeculationLog();

    @Override
    public RootCallTarget createCallTarget(RootNode rootNode) {
        return createClonedCallTarget(null, rootNode);
    }

    @SuppressWarnings("deprecation")
    public RootCallTarget createClonedCallTarget(OptimizedCallTarget source, RootNode rootNode) {
        CompilerAsserts.neverPartOfCompilation();

        OptimizedCallTarget target = createOptimizedCallTarget(source, rootNode);
        rootNode.setCallTarget(target);
        tvmci.onLoad(target.getRootNode());
        return target;
    }

    protected abstract OptimizedCallTarget createOptimizedCallTarget(OptimizedCallTarget source, RootNode rootNode);

    public void addCompilationListener(GraalTruffleCompilationListener listener) {
        compilationListeners.add(listener);
    }

    public void removeCompilationListener(GraalTruffleCompilationListener listener) {
        compilationListeners.remove(listener);
    }

    private void shutdown() {
        getCompilationNotify().notifyShutdown(this);
        OptionValues options = TruffleCompilerOptions.getOptions();
        if (getValue(TruffleInstrumentBranches) || getValue(TruffleInstrumentBoundaries)) {
            instrumentation.dumpAccessTable(options);
        }
    }

    @SuppressWarnings("try")
    protected void doCompile(OptionValues options, OptimizedCallTarget optimizedCallTarget, CancellableCompileTask task) {
        TruffleCompiler compiler = getTruffleCompiler();
        ResolvedJavaMethod rootMethod = compiler.partialEvaluator.rootForCallTarget(optimizedCallTarget);
        CompilationIdentifier compilationId = getCompilationIdentifier(optimizedCallTarget, rootMethod, compiler.backend);
        try (DebugContext debug = openDebugContext(options, compilationId, optimizedCallTarget);
                        DebugContext.Scope s = debug.scope("Truffle", new TruffleDebugJavaMethod(optimizedCallTarget))) {
            compileMethod(debug, compiler, optimizedCallTarget, rootMethod, compilationId, task);
        } catch (Throwable e) {
            optimizedCallTarget.notifyCompilationFailed(e);
        } finally {
            optimizedCallTarget.resetCompilationTask();
        }
    }

    protected abstract DiagnosticsOutputDirectory getDebugOutputDirectory();

    /**
     * Gets the map used to count the number of compilation failures or bailouts handled by each
     * action.
     *
     * @see CompilationWrapper#CompilationWrapper(DiagnosticsOutputDirectory, Map)
     */
    protected abstract Map<ExceptionAction, Integer> getCompilationProblemsPerAction();

    protected final void compileMethod(DebugContext initialDebug,
                    TruffleCompiler compiler,
                    OptimizedCallTarget optimizedCallTarget,
                    ResolvedJavaMethod rootMethod,
                    CompilationIdentifier compilationId,
                    CancellableCompileTask task) {

        CompilationWrapper<Void> compilation = new CompilationWrapper<Void>(getDebugOutputDirectory(), getCompilationProblemsPerAction()) {

            @Override
            public String toString() {
                return optimizedCallTarget.toString();
            }

            @Override
            protected DebugContext createRetryDebugContext(OptionValues options) {
                return openDebugContext(options, compilationId, optimizedCallTarget);
            }

            @Override
            protected Void handleException(Throwable t) {
                optimizedCallTarget.notifyCompilationFailed(t);
                return null;
            }

            @Override
            protected Void performCompilation(DebugContext debug) {
                compiler.compileMethod(debug, optimizedCallTarget, rootMethod, compilationId, task);
                return null;
            }
        };
        compilation.run(initialDebug);
    }

    /**
     * @param optimizedCallTarget
     * @param callRootMethod
     * @param backend
     */
    public CompilationIdentifier getCompilationIdentifier(OptimizedCallTarget optimizedCallTarget, ResolvedJavaMethod callRootMethod, Backend backend) {
        return backend.getCompilationIdentifier(callRootMethod);
    }

    protected abstract BackgroundCompileQueue getCompileQueue();

    @SuppressWarnings("try")
    public CancellableCompileTask submitForCompilation(OptimizedCallTarget optimizedCallTarget) {
        BackgroundCompileQueue l = getCompileQueue();
        final WeakReference<OptimizedCallTarget> weakCallTarget = new WeakReference<>(optimizedCallTarget);
        final OptionValues optionOverrides = TruffleCompilerOptions.getCurrentOptionOverrides();
        CancellableCompileTask cancellable = new CancellableCompileTask();
        cancellable.setFuture(l.compileQueue.submit(new Runnable() {
            @Override
            public void run() {
                OptimizedCallTarget callTarget = weakCallTarget.get();
                if (callTarget != null) {
                    try (TruffleOptionsOverrideScope scope = optionOverrides != null ? overrideOptions(optionOverrides.getMap()) : null) {
                        OptionValues options = TruffleCompilerOptions.getOptions();
                        doCompile(options, callTarget, cancellable);
                    }
                }
            }
        }));
        // task and future must never diverge from each other
        assert cancellable.future != null;
        return cancellable;
    }

    public void finishCompilation(OptimizedCallTarget optimizedCallTarget, Future<?> future, boolean mayBeAsynchronous) {
        getCompilationNotify().notifyCompilationQueued(optimizedCallTarget);

        if (!mayBeAsynchronous) {
            try {
                waitForFutureAndKeepInterrupt(future);
            } catch (ExecutionException e) {
                if (TruffleCompilerOptions.getValue(TruffleCompilationExceptionsAreThrown) && !(e.getCause() instanceof BailoutException && !((BailoutException) e.getCause()).isPermanent())) {
                    throw new RuntimeException(e.getCause());
                } else {
                    // silently ignored
                }
            } catch (CancellationException e) {
                /*
                 * Silently ignored as future might have undergone a "soft" cancel(false).
                 */
            }
        }
    }

    private static void waitForFutureAndKeepInterrupt(Future<?> future) throws ExecutionException {
        // We want to keep the interrupt bit if we are interrupted.
        // But we also want to maintain the semantics of foreground compilation:
        // waiting for the compilation to finish, even if it takes long,
        // so that compilation errors or effects are still properly waited for.
        boolean interrupted = false;
        while (true) {
            try {
                future.get();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean cancelInstalledTask(OptimizedCallTarget optimizedCallTarget, Object source, CharSequence reason) {
        CancellableCompileTask task = optimizedCallTarget.getCompilationTask();
        if (task != null) {
            Future<?> compilationFuture = task.getFuture();
            if (compilationFuture != null && isCompiling(optimizedCallTarget)) {
                optimizedCallTarget.resetCompilationTask();
                /*
                 * Cancellation of an installed task: There are two dimensions here: First we set
                 * the cancel bit in the task, this allows the compiler to, cooperatively, stop
                 * compilation and throw a non permanent bailout and then we cancel the future which
                 * might have already stopped at that point in time.
                 */
                task.cancel();
                // Either the task finished already, or it was cancelled.
                boolean result = !task.isRunning();
                if (result) {
                    optimizedCallTarget.resetCompilationTask();
                    getCompilationNotify().notifyCompilationDequeued(optimizedCallTarget, source, reason);
                }
                return result;
            }
        }
        return false;
    }

    public void waitForCompilation(OptimizedCallTarget optimizedCallTarget, long timeout) throws ExecutionException, TimeoutException {
        CancellableCompileTask task = optimizedCallTarget.getCompilationTask();
        if (task != null) {
            Future<?> compilationFuture = task.getFuture();
            if (compilationFuture != null && isCompiling(optimizedCallTarget)) {
                try {
                    compilationFuture.get(timeout, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    // ignore interrupted
                }
            }
        }

    }

    @Deprecated
    public Collection<OptimizedCallTarget> getQueuedCallTargets() {
        return Collections.emptyList();
    }

    public int getCompilationQueueSize() {
        ExecutorService executor = getCompileQueue().compileQueue;
        if (executor instanceof ThreadPoolExecutor) {
            return ((ThreadPoolExecutor) executor).getQueue().size();
        } else {
            return 0;
        }
    }

    public boolean isCompiling(OptimizedCallTarget optimizedCallTarget) {
        CancellableCompileTask task = optimizedCallTarget.getCompilationTask();
        if (task != null) {
            Future<?> compilationFuture = task.getFuture();
            if (compilationFuture != null) {
                if (compilationFuture.isCancelled() || compilationFuture.isDone()) {
                    optimizedCallTarget.resetCompilationTask();
                    return false;
                }
                return true;
            }
        }
        return false;
    }

    public abstract void invalidateInstalledCode(OptimizedCallTarget optimizedCallTarget, Object source, CharSequence reason);

    public abstract void reinstallStubs();

    public final boolean enableInfopoints() {
        return platformEnableInfopoints();
    }

    protected abstract boolean platformEnableInfopoints();

    protected CallMethods getCallMethods() {
        return callMethods;
    }

    public InstrumentPhase.Instrumentation getInstrumentation() {
        if (instrumentation == null) {
            synchronized (this) {
                if (instrumentation == null) {
                    OptionValues options = TruffleCompilerOptions.getOptions();
                    long[] accessTable = new long[TruffleCompilerOptions.TruffleInstrumentationTableSize.getValue(options)];
                    instrumentation = new InstrumentPhase.Instrumentation(accessTable);
                }
            }
        }
        return instrumentation;
    }

    // cached field access to make it fast in the interpreter
    private Boolean profilingEnabled;

    @Override
    public final boolean isProfilingEnabled() {
        if (profilingEnabled == null) {
            profilingEnabled = TruffleCompilerOptions.getValue(TruffleProfilingEnabled);
        }
        return profilingEnabled;
    }

    private static Object loadObjectLayoutFactory() {
        ServiceLoader<LayoutFactory> graalLoader = ServiceLoader.load(LayoutFactory.class, GraalTruffleRuntime.class.getClassLoader());
        if (Java8OrEarlier) {
            return selectObjectLayoutFactory(graalLoader);
        } else {
            /*
             * The Graal module (i.e., jdk.internal.vm.compiler) is loaded by the platform class
             * loader on JDK 9. Its module dependencies such as Truffle are supplied via
             * --module-path which means they are loaded by the app class loader. As such, we need
             * to search the app class loader path as well.
             */
            ServiceLoader<LayoutFactory> appLoader = ServiceLoader.load(LayoutFactory.class, LayoutFactory.class.getClassLoader());
            return selectObjectLayoutFactory(CollectionsUtil.concat(graalLoader, appLoader));
        }
    }

    protected static LayoutFactory selectObjectLayoutFactory(Iterable<LayoutFactory> availableLayoutFactories) {
        String layoutFactoryImplName = System.getProperty("truffle.object.LayoutFactory");
        LayoutFactory bestLayoutFactory = null;
        for (LayoutFactory currentLayoutFactory : availableLayoutFactories) {
            if (layoutFactoryImplName != null) {
                if (currentLayoutFactory.getClass().getName().equals(layoutFactoryImplName)) {
                    return currentLayoutFactory;
                }
            } else {
                if (bestLayoutFactory == null) {
                    bestLayoutFactory = currentLayoutFactory;
                } else if (currentLayoutFactory.getPriority() >= bestLayoutFactory.getPriority()) {
                    assert currentLayoutFactory.getPriority() != bestLayoutFactory.getPriority();
                    bestLayoutFactory = currentLayoutFactory;
                }
            }
        }
        return bestLayoutFactory;
    }

    private final class DispatchTruffleCompilationListener implements GraalTruffleCompilationListener {

        @Override
        public void notifyCompilationQueued(OptimizedCallTarget target) {
            for (GraalTruffleCompilationListener l : compilationListeners) {
                l.notifyCompilationQueued(target);
            }
        }

        @Override
        public void notifyCompilationInvalidated(OptimizedCallTarget target, Object source, CharSequence reason) {
            for (GraalTruffleCompilationListener l : compilationListeners) {
                l.notifyCompilationInvalidated(target, source, reason);
            }
        }

        @Override
        public void notifyCompilationDequeued(OptimizedCallTarget target, Object source, CharSequence reason) {
            for (GraalTruffleCompilationListener l : compilationListeners) {
                l.notifyCompilationDequeued(target, source, reason);
            }
        }

        @Override
        public void notifyCompilationFailed(OptimizedCallTarget target, StructuredGraph graph, Throwable t) {
            for (GraalTruffleCompilationListener l : compilationListeners) {
                l.notifyCompilationFailed(target, graph, t);
            }
        }

        @Override
        public void notifyCompilationSplit(OptimizedDirectCallNode callNode) {
            for (GraalTruffleCompilationListener l : compilationListeners) {
                l.notifyCompilationSplit(callNode);
            }
        }

        @Override
        public void notifyCompilationGraalTierFinished(OptimizedCallTarget target, StructuredGraph graph) {
            for (GraalTruffleCompilationListener l : compilationListeners) {
                l.notifyCompilationGraalTierFinished(target, graph);
            }
        }

        @Override
        public void notifyCompilationDeoptimized(OptimizedCallTarget target, Frame frame) {
            for (GraalTruffleCompilationListener l : compilationListeners) {
                l.notifyCompilationDeoptimized(target, frame);
            }
        }

        @Override
        public void notifyCompilationSuccess(OptimizedCallTarget target, TruffleInlining inliningDecision, StructuredGraph graph, CompilationResult result) {
            for (GraalTruffleCompilationListener l : compilationListeners) {
                l.notifyCompilationSuccess(target, inliningDecision, graph, result);
            }
        }

        @Override
        public void notifyCompilationStarted(OptimizedCallTarget target) {
            for (GraalTruffleCompilationListener l : compilationListeners) {
                l.notifyCompilationStarted(target);
            }
        }

        @Override
        public void notifyCompilationTruffleTierFinished(OptimizedCallTarget target, TruffleInlining inliningDecision, StructuredGraph graph) {
            for (GraalTruffleCompilationListener l : compilationListeners) {
                l.notifyCompilationTruffleTierFinished(target, inliningDecision, graph);
            }
        }

        @Override
        public void notifyShutdown(GraalTruffleRuntime runtime) {
            for (GraalTruffleCompilationListener l : compilationListeners) {
                l.notifyShutdown(runtime);
            }
        }

        @Override
        public void notifyStartup(GraalTruffleRuntime runtime) {
            for (GraalTruffleCompilationListener l : compilationListeners) {
                l.notifyStartup(runtime);
            }
        }

    }

    protected static final class CallMethods {
        public final ResolvedJavaMethod callNodeMethod;
        public final ResolvedJavaMethod callTargetMethod;
        public final ResolvedJavaMethod callOSRMethod;
        public final ResolvedJavaMethod[] anyFrameMethod;

        private CallMethods(MetaAccessProvider metaAccess) {
            this.callNodeMethod = metaAccess.lookupJavaMethod(GraalFrameInstance.CALL_NODE_METHOD);
            this.callTargetMethod = metaAccess.lookupJavaMethod(GraalFrameInstance.CALL_TARGET_METHOD);
            this.callOSRMethod = metaAccess.lookupJavaMethod(GraalFrameInstance.CALL_OSR_METHOD);
            this.anyFrameMethod = new ResolvedJavaMethod[]{callNodeMethod, callTargetMethod, callOSRMethod};
        }

        public static CallMethods lookup(MetaAccessProvider metaAccess) {
            return new CallMethods(metaAccess);
        }
    }

    public static class LazyFrameBoxingQuery {
        /**
         * The flag is checked from within a Truffle compilation and we need to constant fold the
         * decision. In addition, we want only one of {@link FrameWithoutBoxing} and
         * {@link FrameWithBoxing} seen as reachable in AOT mode, so we need to be able to constant
         * fold the decision as early as possible.
         */
        public static final boolean useFrameWithoutBoxing = TruffleCompilerOptions.getValue(TruffleUseFrameWithoutBoxing);
    }
}
