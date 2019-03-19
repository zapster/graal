/*
 * Copyright (c) 2015, 2016, Oracle and/or its affiliates. All rights reserved.
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.debug.DebuggerNode.InputValuesProvider;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.frame.FrameInstanceVisitor;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.instrumentation.Instrumenter;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;

/**
 * Access for {@link Debugger} clients to the state of a guest language execution thread that has
 * been suspended, for example by a {@link Breakpoint} or stepping action.
 * <p>
 * <h4>Event lifetime</h4>
 * <ul>
 * <li>A {@link DebuggerSession} {@link Instrumenter instruments} guest language code in order to
 * implement {@linkplain Breakpoint breakpoints}, stepping actions, or other debugging actions on
 * behalf of the session's {@linkplain Debugger debugger} client.</li>
 *
 * <li>A session may choose to suspend a guest language execution thread when it receives
 * (synchronous) notification on the execution thread that it has reached an AST location
 * instrumented by the session.</li>
 *
 * <li>The session passes a new {@link SuspendedEvent} to the debugger client (synchronously) in a
 * {@linkplain SuspendedCallback#onSuspend(SuspendedEvent) callback} on the guest language execution
 * thread.</li>
 *
 * <li>Clients may access certain event state only on the execution thread where the event was
 * created and notification received; access from other threads can throws
 * {@link IllegalStateException}. Please see the javadoc of the individual method for details.</li>
 *
 * <li>A suspended thread resumes guest language execution after the client callback returns and the
 * thread unwinds back to instrumentation code in the AST.</li>
 *
 * <li>All event methods throw {@link IllegalStateException} after the suspended thread resumes
 * guest language execution.</li>
 * </ul>
 * </p>
 * <h4>Access to execution state</h4>
 * <p>
 * <ul>
 * <li>Method {@link #getStackFrames()} describes the suspended thread's location in guest language
 * code. This information becomes unusable beyond the lifetime of the event and must not be stored.
 * </li>
 *
 * <li>Method {@link #getReturnValue()} describes a local result when the thread is suspended just
 * {@link SuspendAnchor#AFTER after} the code source section.</li>
 * </ul>
 * </p>
 * <h4>Next debugging action</h4>
 * <p>
 * Clients use the following methods to request the debugging action(s) that will take effect when
 * the event's thread resumes guest language execution. All prepare requests accumulate until
 * resumed.
 * <ul>
 * <li>{@link #prepareStepInto(int)}</li>
 * <li>{@link #prepareStepOut(int)}</li>
 * <li>{@link #prepareStepOver(int)}</li>
 * <li>{@link #prepareKill()}</li>
 * <li>{@link #prepareContinue()}</li>
 * </ul>
 * If no debugging action is requested then {@link #prepareContinue() continue} is assumed.
 * </p>
 *
 * @since 0.9
 */

public final class SuspendedEvent {

    private final SourceSection sourceSection;
    private final SuspendAnchor suspendAnchor;

    private final Thread thread;

    private DebuggerSession session;
    private SuspendedContext context;
    private MaterializedFrame materializedFrame;
    private InsertableNode insertableNode;
    private List<Breakpoint> breakpoints;
    private InputValuesProvider inputValuesProvider;
    private Object returnValue;
    private DebugException exception;

    private volatile boolean disposed;
    private volatile SteppingStrategy nextStrategy;

    private final Map<Breakpoint, Throwable> conditionFailures;
    private DebugStackFrameIterable cachedFrames;

    SuspendedEvent(DebuggerSession session, Thread thread, SuspendedContext context, MaterializedFrame frame, SuspendAnchor suspendAnchor,
                    InsertableNode insertableNode, InputValuesProvider inputValuesProvider, Object returnValue, DebugException exception,
                    List<Breakpoint> breakpoints, Map<Breakpoint, Throwable> conditionFailures) {
        this.session = session;
        this.context = context;
        this.suspendAnchor = suspendAnchor;
        this.materializedFrame = frame;
        this.insertableNode = insertableNode;
        this.inputValuesProvider = inputValuesProvider;
        this.returnValue = returnValue;
        this.exception = exception;
        this.conditionFailures = conditionFailures;
        this.breakpoints = breakpoints == null ? Collections.<Breakpoint> emptyList() : Collections.<Breakpoint> unmodifiableList(breakpoints);
        this.thread = thread;
        this.sourceSection = context.getInstrumentedSourceSection();
    }

    boolean isDisposed() {
        return disposed;
    }

    void clearLeakingReferences() {
        this.disposed = true;

        // cleanup data for potential memory leaks
        this.inputValuesProvider = null;
        this.returnValue = null;
        this.exception = null;
        this.breakpoints = null;
        this.materializedFrame = null;
        this.cachedFrames = null;
        this.session = null;
        this.context = null;
        this.insertableNode = null;
    }

    void verifyValidState(boolean allowDifferentThread) {
        if (disposed) {
            throw new IllegalStateException("Not in a suspended state.");
        }
        if (!allowDifferentThread && Thread.currentThread() != thread) {
            throw new IllegalStateException("Illegal thread access.");
        }
    }

    SteppingStrategy getNextStrategy() {
        SteppingStrategy strategy = nextStrategy;
        if (strategy == null) {
            return SteppingStrategy.createContinue();
        }
        return strategy;
    }

    private synchronized void setNextStrategy(SteppingStrategy nextStrategy) {
        verifyValidState(true);
        if (this.nextStrategy == null) {
            this.nextStrategy = nextStrategy;
        } else if (this.nextStrategy.isKill()) {
            throw new IllegalStateException("Calls to prepareKill() cannot be followed by any other preparation call.");
        } else if (this.nextStrategy.isDone()) {
            throw new IllegalStateException("Calls to prepareContinue() cannot be followed by any other preparation call.");
        } else if (this.nextStrategy.isComposable()) {
            this.nextStrategy.add(nextStrategy);
        } else {
            this.nextStrategy = SteppingStrategy.createComposed(this.nextStrategy, nextStrategy);
        }
    }

    /**
     * Returns the debugger session this suspended event was created for.
     * <p>
     * This method is thread-safe.
     *
     * @since 0.17
     */
    public DebuggerSession getSession() {
        verifyValidState(true);
        return session;
    }

    Thread getThread() {
        return thread;
    }

    SuspendedContext getContext() {
        return context;
    }

    InsertableNode getInsertableNode() {
        return insertableNode;
    }

    /**
     * Returns the guest language source section of the AST node before/after the execution is
     * suspended. Returns <code>null</code> if no source section information is available.
     * <p>
     * This method is thread-safe.
     *
     * @since 0.17
     */
    public SourceSection getSourceSection() {
        verifyValidState(true);
        return sourceSection;
    }

    /**
     * Returns where, within the guest language {@link #getSourceSection() source section}, the
     * suspended position is.
     *
     * @since 0.32
     */
    public SuspendAnchor getSuspendAnchor() {
        verifyValidState(true);
        return suspendAnchor;
    }

    /**
     * Returns <code>true</code> if the underlying guest language source location is denoted as the
     * source element.
     *
     * @param sourceElement the source element to check, must not be <code>null</code>.
     * @since 0.33
     */
    public boolean hasSourceElement(SourceElement sourceElement) {
        return context.hasTag(sourceElement.getTag());
    }

    /**
     * Test if the language context of the source of the event is initialized.
     *
     * @since 0.26
     */
    public boolean isLanguageContextInitialized() {
        verifyValidState(true);
        return context.isLanguageContextInitialized();
    }

    /**
     * Returns the input values of the current source element gathered from return values of it's
     * executed children. The input values are available only during stepping through the source
     * elements hierarchy and only on {@link SuspendAnchor#AFTER AFTER} {@link #getSuspendAnchor()
     * suspend anchor}. There can be <code>null</code> values in the returned array for children we
     * did not intercept return values from.
     *
     * @return the array of input values, or <code>null</code> when no input is available.
     * @since 0.33
     */
    public DebugValue[] getInputValues() {
        if (inputValuesProvider == null) {
            return null;
        }
        Object[] inputValues = inputValuesProvider.getDebugInputValues(materializedFrame);
        int n = inputValues.length;
        DebugValue[] values = new DebugValue[n];
        for (int i = 0; i < n; i++) {
            if (inputValues[i] != null) {
                values[i] = getTopStackFrame().wrapHeapValue(inputValues[i]);
            } else {
                values[i] = null;
            }
        }
        return values;
    }

    /**
     * Returns the return value of the currently executed source location. Returns <code>null</code>
     * if the execution is suspended {@link SuspendAnchor#BEFORE before} a guest language location.
     * The returned value is <code>null</code> if an exception occurred during execution of the
     * instrumented source element, the exception is provided by {@link #getException()}.
     * <p>
     * This method is not thread-safe and will throw an {@link IllegalStateException} if called on
     * another thread than it was created with.
     *
     * @since 0.17
     */
    public DebugValue getReturnValue() {
        verifyValidState(false);
        Object ret = returnValue;
        if (ret == null) {
            return null;
        }
        return getTopStackFrame().wrapHeapValue(ret);
    }

    /**
     * Returns the debugger representation of a guest language exception that caused this suspended
     * event (via an exception breakpoint, for instance). Returns <code>null</code> when no
     * exception occurred.
     *
     * @since 1.0
     */
    public DebugException getException() {
        return exception;
    }

    MaterializedFrame getMaterializedFrame() {
        return materializedFrame;
    }

    /**
     * Returns the cause of failure, if any, during evaluation of a breakpoint's
     * {@linkplain Breakpoint#setCondition(String) condition}.
     *
     * <p>
     * This method is thread-safe.
     *
     * @param breakpoint a breakpoint associated with this event
     * @return the cause of condition failure
     *
     * @since 0.17
     */
    public Throwable getBreakpointConditionException(Breakpoint breakpoint) {
        verifyValidState(true);
        if (conditionFailures == null) {
            return null;
        }
        return conditionFailures.get(breakpoint);
    }

    /**
     * Returns the {@link Breakpoint breakpoints} that individually would cause the "hit" where
     * execution is suspended. If {@link Debugger#install(com.oracle.truffle.api.debug.Breakpoint)
     * Debugger-associated} breakpoint was hit, it is not possible to change the state of returned
     * breakpoint.
     * <p>
     * This method is thread-safe.
     *
     * @return an unmodifiable list of breakpoints
     *
     * @since 0.17
     */
    public List<Breakpoint> getBreakpoints() {
        verifyValidState(true);
        return breakpoints;
    }

    /**
     * Returns the topmost stack frame returned by {@link #getStackFrames()}.
     * <p>
     * This method is not thread-safe and will throw an {@link IllegalStateException} if called on
     * another thread than it was created with.
     *
     * @see #getStackFrames()
     * @since 0.17
     */
    public DebugStackFrame getTopStackFrame() {
        // there must be always a top stack frame.
        return getStackFrames().iterator().next();
    }

    /**
     * Returns a list of guest language stack frame objects that indicate the current guest language
     * location. There is always at least one, the topmost, stack frame available. The returned
     * stack frames are usable only during {@link SuspendedCallback#onSuspend(SuspendedEvent)
     * suspend} and should not be stored permanently.
     *
     * <p>
     * This method is not thread-safe and will throw an {@link IllegalStateException} if called on
     * another thread than it was created with.
     *
     * @since 0.17
     */
    public Iterable<DebugStackFrame> getStackFrames() {
        verifyValidState(false);
        if (cachedFrames == null) {
            cachedFrames = new DebugStackFrameIterable();
        }
        return cachedFrames;
    }

    static boolean isEvalRootStackFrame(DebuggerSession session, FrameInstance instance) {
        CallTarget target = instance.getCallTarget();
        RootNode root = null;
        if (target instanceof RootCallTarget) {
            root = ((RootCallTarget) target).getRootNode();
        }
        if (root != null && session.getDebugger().getEnv().isEngineRoot(root)) {
            return true;
        }
        return false;
    }

    /**
     * Prepare to execute in Continue mode when guest language program execution resumes. In this
     * mode execution will continue until either:
     * <ul>
     * <li>execution arrives at a node to which an enabled breakpoint is attached,
     * <strong>or:</strong></li>
     * <li>execution completes.</li>
     * </ul>
     * <p>
     * This method is thread-safe and the prepared Continue mode is appended to any other previously
     * prepared modes. No further modes can be prepared after continue.
     *
     * @throws IllegalStateException when {@link #prepareContinue() continue} or
     *             {@link #prepareKill() kill} is prepared already.
     * @since 0.9
     */
    public void prepareContinue() {
        setNextStrategy(SteppingStrategy.createContinue());
    }

    /**
     * Prepare to execute in <strong>step into</strong> mode when guest language program execution
     * resumes. See the description of {@link #prepareStepInto(StepConfig)} for details, calling
     * this is identical to
     * <code>{@link #prepareStepInto(StepConfig) prepareStepInto}.({@link StepConfig StepConfig}.{@link StepConfig#newBuilder() newBuilder}().{@link StepConfig.Builder#count(int) count}(stepCount).{@link StepConfig.Builder#build() build}())</code>
     * .
     *
     * @param stepCount the number of times to perform StepInto before halting
     * @return this event instance for an easy concatenation of method calls
     * @throws IllegalArgumentException if {@code stepCount <= 0}
     * @throws IllegalStateException when {@link #prepareContinue() continue} or
     *             {@link #prepareKill() kill} is prepared already.
     * @since 0.9
     */
    public SuspendedEvent prepareStepInto(int stepCount) {
        return prepareStepInto(StepConfig.newBuilder().count(stepCount).build());
    }

    /**
     * Prepare to execute in <strong>step out</strong> mode when guest language program execution
     * resumes. See the description of {@link #prepareStepOut(StepConfig)} for details, calling this
     * is identical to
     * <code>{@link #prepareStepOut(StepConfig) prepareStepOut}.({@link StepConfig StepConfig}.{@link StepConfig#newBuilder() newBuilder}().{@link StepConfig.Builder#count(int) count}(stepCount).{@link StepConfig.Builder#build() build}())</code>
     * .
     *
     * @param stepCount the number of times to perform StepOver before halting
     * @return this event instance for an easy concatenation of method calls
     * @throws IllegalArgumentException if {@code stepCount <= 0}
     * @throws IllegalStateException when {@link #prepareContinue() continue} or
     *             {@link #prepareKill() kill} is prepared already.
     * @since 0.26
     */
    public SuspendedEvent prepareStepOut(int stepCount) {
        return prepareStepOut(StepConfig.newBuilder().count(stepCount).build());
    }

    /**
     * Prepare to execute in <strong>step over</strong> mode when guest language program execution
     * resumes. See the description of {@link #prepareStepOver(StepConfig)} for details, calling
     * this is identical to
     * <code>{@link #prepareStepOver(StepConfig) prepareStepOver}.({@link StepConfig StepConfig}.{@link StepConfig#newBuilder() newBuilder}().{@link StepConfig.Builder#count(int) count}(stepCount).{@link StepConfig.Builder#build() build}())</code>
     * .
     *
     * @param stepCount the number of times to perform step over before halting
     * @return this event instance for an easy concatenation of method calls
     * @throws IllegalArgumentException if {@code stepCount <= 0}
     * @throws IllegalStateException when {@link #prepareContinue() continue} or
     *             {@link #prepareKill() kill} is prepared already.
     * @since 0.9
     */
    public SuspendedEvent prepareStepOver(int stepCount) {
        return prepareStepOver(StepConfig.newBuilder().count(stepCount).build());
    }

    /**
     * Prepare to execute in <strong>step into</strong> mode when guest language program execution
     * resumes. In this mode, the current thread continues until it arrives to a code location with
     * one of the enabled {@link StepConfig.Builder#sourceElements(SourceElement...) source
     * elements} and repeats that process {@link StepConfig.Builder#count(int) step count} times.
     * See {@link StepConfig} for the details about the stepping behavior.
     * <p>
     * This mode persists until the thread resumes and then suspends, at which time the mode reverts
     * to {@linkplain #prepareContinue() Continue}, or the thread dies.
     * <p>
     * A breakpoint set at a location where execution would suspend is treated specially as a single
     * event, to avoid multiple suspensions at a single location.
     * <p>
     * This method is thread-safe and the prepared StepInto mode is appended to any other previously
     * prepared modes.
     *
     * @param stepConfig the step configuration
     * @return this event instance for an easy concatenation of method calls
     * @throws IllegalStateException when {@link #prepareContinue() continue} or
     *             {@link #prepareKill() kill} is prepared already, or when the current debugger
     *             session has no source elements enabled for stepping.
     * @throws IllegalArgumentException when the {@link StepConfig} contains source elements not
     *             enabled for stepping in the current debugger session.
     * @since 0.33
     */
    public SuspendedEvent prepareStepInto(StepConfig stepConfig) {
        verifyConfig(stepConfig);
        setNextStrategy(SteppingStrategy.createStepInto(session, stepConfig));
        return this;
    }

    /**
     * Prepare to execute in <strong>step out</strong> mode when guest language program execution
     * resumes. In this mode, the current thread continues until it arrives to an enclosing code
     * location with one of the enabled {@link StepConfig.Builder#sourceElements(SourceElement...)
     * source elements} and repeats that process {@link StepConfig.Builder#count(int) step count}
     * times. See {@link StepConfig} for the details about the stepping behavior.
     * <p>
     * This mode persists until the thread resumes and then suspends, at which time the mode reverts
     * to {@linkplain #prepareContinue() Continue}, or the thread dies.
     * <p>
     * A breakpoint set at a location where execution would suspend is treated specially as a single
     * event, to avoid multiple suspensions at a single location.
     * <p>
     * This method is thread-safe and the prepared StepInto mode is appended to any other previously
     * prepared modes.
     *
     * @param stepConfig the step configuration
     * @return this event instance for an easy concatenation of method calls
     * @throws IllegalStateException when {@link #prepareContinue() continue} or
     *             {@link #prepareKill() kill} is prepared already, or when the current debugger
     *             session has no source elements enabled for stepping.
     * @throws IllegalArgumentException when the {@link StepConfig} contains source elements not
     *             enabled for stepping in the current debugger session.
     * @since 0.33
     */
    public SuspendedEvent prepareStepOut(StepConfig stepConfig) {
        verifyConfig(stepConfig);
        setNextStrategy(SteppingStrategy.createStepOut(session, stepConfig));
        return this;
    }

    /**
     * Prepare to execute in <strong>step out</strong> mode when guest language program execution
     * resumes. In this mode, the current thread continues until it arrives to a code location with
     * one of the enabled {@link StepConfig.Builder#sourceElements(SourceElement...) source
     * elements}, ignoring any nested ones, and repeats that process
     * {@link StepConfig.Builder#count(int) step count} times. See {@link StepConfig} for the
     * details about the stepping behavior.
     * <p>
     * This mode persists until the thread resumes and then suspends, at which time the mode reverts
     * to {@linkplain #prepareContinue() Continue}, or the thread dies.
     * <p>
     * A breakpoint set at a location where execution would suspend is treated specially as a single
     * event, to avoid multiple suspensions at a single location.
     * <p>
     * This method is thread-safe and the prepared StepInto mode is appended to any other previously
     * prepared modes.
     *
     * @param stepConfig the step configuration
     * @return this event instance for an easy concatenation of method calls
     * @throws IllegalStateException when {@link #prepareContinue() continue} or
     *             {@link #prepareKill() kill} is prepared already, or when the current debugger
     *             session has no source elements enabled for stepping.
     * @throws IllegalArgumentException when the {@link StepConfig} contains source elements not
     *             enabled for stepping in the current debugger session.
     * @since 0.33
     */
    public SuspendedEvent prepareStepOver(StepConfig stepConfig) {
        verifyConfig(stepConfig);
        setNextStrategy(SteppingStrategy.createStepOver(session, stepConfig));
        return this;
    }

    private void verifyConfig(StepConfig stepConfig) {
        Set<SourceElement> sessionElements = session.getSourceElements();
        if (sessionElements.isEmpty()) {
            throw new IllegalStateException("No source elements are enabled for stepping in the debugger session.");
        }
        Set<SourceElement> stepElements = stepConfig.getSourceElements();
        if (stepElements != null && !sessionElements.containsAll(stepElements)) {
            Set<SourceElement> extraElements = new HashSet<>(stepElements);
            extraElements.removeAll(sessionElements);
            throw new IllegalArgumentException("The step source elements " + extraElements + " are not enabled in the session.");
        }
    }

    /**
     * Prepare to unwind a frame. This frame and all frames above it are unwound off the execution
     * stack. The frame needs to be on the {@link #getStackFrames() execution stack of this event}.
     *
     * @param frame the frame to unwind
     * @throws IllegalArgumentException when the frame is not on the execution stack of this event
     * @since 0.31
     */
    public void prepareUnwindFrame(DebugStackFrame frame) throws IllegalArgumentException {
        if (frame.event != this) {
            throw new IllegalArgumentException("The stack frame is not in the scope of this event.");
        }
        setNextStrategy(SteppingStrategy.createUnwind(frame.getDepth()));
    }

    /**
     * Prepare to terminate the suspended execution represented by this event. One use-case for this
     * method is to shield an execution of an unknown code with a timeout:
     *
     * <p>
     * This method is thread-safe and the prepared termination is appended to any other previously
     * prepared modes. No further modes can be prepared after kill.
     *
     * @throws IllegalStateException when {@link #prepareContinue() continue} or
     *             {@link #prepareKill() kill} is prepared already.
     * @since 0.12
     */
    public void prepareKill() {
        setNextStrategy(SteppingStrategy.createKill());
    }

    /**
     * @since 0.17
     */
    @Override
    public String toString() {
        return "Suspended at " + getSourceSection() + " for thread " + getThread();
    }

    private final class DebugStackFrameIterable implements Iterable<DebugStackFrame> {

        private DebugStackFrame topStackFrame;
        private List<DebugStackFrame> otherFrames;

        private DebugStackFrame getTopStackFrame() {
            if (topStackFrame == null) {
                topStackFrame = new DebugStackFrame(SuspendedEvent.this, null, 0);
            }
            return topStackFrame;
        }

        private List<DebugStackFrame> getOtherFrames() {
            if (otherFrames == null) {
                final List<DebugStackFrame> frameInstances = new ArrayList<>();
                Truffle.getRuntime().iterateFrames(new FrameInstanceVisitor<FrameInstance>() {
                    private int depth = -context.getStackDepth() - 1;

                    @Override
                    public FrameInstance visitFrame(FrameInstance frameInstance) {
                        if (isEvalRootStackFrame(session, frameInstance)) {
                            // we stop at eval root stack frames
                            return frameInstance;
                        }
                        if (++depth <= 0) {
                            return null;
                        }
                        frameInstances.add(new DebugStackFrame(SuspendedEvent.this, frameInstance, depth));
                        return null;
                    }
                });
                otherFrames = frameInstances;
            }
            return otherFrames;
        }

        public Iterator<DebugStackFrame> iterator() {
            return new Iterator<DebugStackFrame>() {

                private int index;
                private Iterator<DebugStackFrame> otherIterator;

                public boolean hasNext() {
                    verifyValidState(false);
                    if (index == 0) {
                        return true;
                    } else {
                        return getOtherStackFrames().hasNext();
                    }
                }

                public DebugStackFrame next() {
                    verifyValidState(false);
                    if (index == 0) {
                        index++;
                        return getTopStackFrame();
                    } else {
                        return getOtherStackFrames().next();
                    }
                }

                public void remove() {
                    throw new UnsupportedOperationException();
                }

                private Iterator<DebugStackFrame> getOtherStackFrames() {
                    if (otherIterator == null) {
                        otherIterator = getOtherFrames().iterator();
                    }
                    return otherIterator;
                }

            };
        }

    }

}
