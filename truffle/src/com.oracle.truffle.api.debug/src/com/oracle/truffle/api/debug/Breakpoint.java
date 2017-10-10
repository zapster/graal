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

import java.net.URI;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.debug.DebuggerSession.SteppingLocation;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.EventBinding;
import com.oracle.truffle.api.instrumentation.EventContext;
import com.oracle.truffle.api.instrumentation.ExecutionEventNode;
import com.oracle.truffle.api.instrumentation.ExecutionEventNodeFactory;
import com.oracle.truffle.api.instrumentation.LoadSourceSectionEvent;
import com.oracle.truffle.api.instrumentation.LoadSourceSectionListener;
import com.oracle.truffle.api.instrumentation.SourceSectionFilter;
import com.oracle.truffle.api.instrumentation.SourceSectionFilter.IndexRange;
import com.oracle.truffle.api.instrumentation.SourceSectionFilter.SourcePredicate;
import com.oracle.truffle.api.instrumentation.StandardTags.StatementTag;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.nodes.SlowPathException;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.api.vm.PolyglotEngine;

/**
 * A request that guest language program execution be suspended at specified locations on behalf of
 * a debugging client {@linkplain DebuggerSession session}.
 * <p>
 * <h4>Breakpoint lifetime</h4>
 * <p>
 * <ul>
 * <li>A client of a {@link DebuggerSession} uses a {@link Builder} to create a new breakpoint,
 * choosing among multiple ways to specify the intended location. Examples include a specified
 * {@link #newBuilder(Source) source}, a specified {@link #newBuilder(URI) URI}, line ranges, or an
 * exact {@link #newBuilder(SourceSection) SourceSection}.</li>
 *
 * <li>A new breakpoint cannot affect execution until after it has been
 * {@linkplain DebuggerSession#install(Breakpoint) installed} by a session, which may be done only
 * once.</li>
 *
 * <li>A breakpoint that is both installed and {@linkplain Breakpoint#isEnabled() enabled} (true by
 * default) will suspend any guest language execution thread that arrives at a matching AST
 * location. The breakpoint (synchronously) {@linkplain SuspendedCallback calls back} to the
 * responsible session on the execution thread.</li>
 *
 * <li>A breakpoint may be enabled or disabled any number of times.</li>
 *
 * <li>A breakpoint that is no longer needed may be {@linkplain #dispose() disposed}. A disposed
 * breakpoint:
 * <ul>
 * <li>is disabled</li>
 * <li>is not installed in any session</li>
 * <li>can have no effect on program execution, and</li>
 * <li>must not be used again.</li>
 * </ul>
 * </li>
 *
 * <li>A session being {@linkplain DebuggerSession#close() closed} disposes all installed
 * breakpoints.</li>
 * </ul>
 * </p>
 * <p>
 * Example usage: {@link com.oracle.truffle.api.debug.BreakpointSnippets#example()}
 *
 * @since 0.9
 */
public class Breakpoint {

    private static final Breakpoint BUILDER_INSTANCE = new Breakpoint();

    private final SourceSectionFilter filter;
    private final BreakpointLocation locationKey;
    private final boolean oneShot;

    private volatile Debugger debugger;
    private final List<DebuggerSession> sessions = new LinkedList<>();
    private volatile Assumption sessionsUnchanged;

    private volatile boolean enabled;
    private volatile boolean resolved;
    private volatile int ignoreCount;
    private volatile boolean disposed;
    private volatile String condition;
    private volatile boolean global;
    private volatile GlobalBreakpoint roWrapper;

    /* We use long instead of int in the implementation to avoid not hitting again on overflows. */
    private final AtomicLong hitCount = new AtomicLong();
    private volatile Assumption conditionUnchanged;

    private EventBinding<? extends ExecutionEventNodeFactory> breakpointBinding;
    private EventBinding<?> sourceBinding;

    Breakpoint(BreakpointLocation key, SourceSectionFilter filter, boolean oneShot) {
        this.locationKey = key;
        this.filter = filter;
        this.oneShot = oneShot;
        this.enabled = true;
    }

    private Breakpoint() {
        this.locationKey = null;
        this.filter = null;
        this.oneShot = false;
    }

    /**
     * @return whether this breakpoint is permanently unable to affect execution
     * @see #dispose()
     *
     * @since 0.17
     */
    public boolean isDisposed() {
        return disposed;
    }

    /**
     * @return whether this breakpoint is currently allowed to suspend execution (true by default)
     * @see #setEnabled(boolean)
     *
     * @since 0.9
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Controls whether this breakpoint is currently allowed to suspend execution (true by default).
     * This can be changed arbitrarily until breakpoint is {@linkplain #dispose() disposed}.
     * <p>
     * When not {@link #isModifiable() modifiable}, {@link IllegalStateException} is thrown.
     *
     * @param enabled whether this breakpoint should be allowed to suspend execution
     *
     * @since 0.9
     */
    public synchronized void setEnabled(boolean enabled) {
        if (disposed) {
            // cannot enable disposed breakpoints
            return;
        }
        if (this.enabled != enabled) {
            if (!sessions.isEmpty()) {
                if (enabled) {
                    install();
                } else {
                    uninstall();
                }
            }
            this.enabled = enabled;
        }
    }

    /**
     * @return whether at least one source has been loaded that contains a match for this
     *         breakpoint's location.
     *
     * @since 0.17
     */
    public boolean isResolved() {
        return resolved;
    }

    /**
     * Assigns to this breakpoint a boolean expression whose evaluation will determine whether the
     * breakpoint suspends execution (i.e. "hits"), {@code null} to remove any condition and always
     * suspend.
     * <p>
     * Breakpoints are by default unconditional.
     * </p>
     * <p>
     * <strong>Evaluation:</strong> expressions are parsed and evaluated in the lexical context of
     * the breakpoint's location. A conditional breakpoint that applies to multiple code locations
     * will be parsed and evaluated separately for each location.
     * </p>
     * <p>
     * <strong>Evaluation failure:</strong> when evaluation of a condition fails for any reason,
     * including the return of a non-boolean value:
     * <ul>
     * <li>execution suspends, as if evaluation had returned {@code true}, and</li>
     * <li>a message is logged that can be
     * {@linkplain SuspendedEvent#getBreakpointConditionException(Breakpoint) retrieved} while
     * execution is suspended.</li>
     * </ul>
     * When not {@link #isModifiable() modifiable}, {@link IllegalStateException} is thrown.
     *
     * @param expression if non{@code -null}, a boolean expression, expressed in the guest language
     *            of the breakpoint's location.
     * @see SuspendedEvent#getBreakpointConditionException(Breakpoint)
     *
     * @since 0.9
     */
    public synchronized void setCondition(String expression) {
        this.condition = expression;
        Assumption assumption = conditionUnchanged;
        if (assumption != null) {
            this.conditionUnchanged = null;
            assumption.invalidate();
        }
    }

    /**
     * Returns the expression used to create the current breakpoint condition, null if no condition
     * set.
     *
     * @since 0.20
     */
    @SuppressFBWarnings("UG")
    public String getCondition() {
        return condition;
    }

    /**
     * Permanently prevents this breakpoint from affecting execution. When not
     * {@link #isModifiable() modifiable}, {@link IllegalStateException} is thrown.
     *
     * @since 0.9
     */
    public synchronized void dispose() {
        if (!disposed) {
            setEnabled(false);
            if (sourceBinding != null) {
                sourceBinding.dispose();
                sourceBinding = null;
            }
            for (DebuggerSession session : sessions) {
                session.disposeBreakpoint(this);
            }
            if (debugger != null) {
                debugger.disposeBreakpoint(this);
                debugger = null;
            }
            disposed = true;
        }
    }

    /**
     * @return whether this breakpoint disables itself after suspending execution, i.e. on first hit
     *
     * @since 0.9
     */
    public boolean isOneShot() {
        return oneShot;
    }

    /**
     * @return the number of times breakpoint will be executed but not hit (i.e. suspend execution).
     * @see #setIgnoreCount(int)
     *
     * @since 0.9
     */
    public int getIgnoreCount() {
        return ignoreCount;
    }

    /**
     * Changes the number of times the breakpoint must be executed before it hits (i.e. suspends
     * execution).
     * <p>
     * When a breakpoint {@linkplain #setCondition(String) condition} evaluates to {@code false}:
     * <ul>
     * <li>execution is <em>not</em> suspended</li>
     * <li>it does not count as a hit</li>
     * <li>the remaining {@code ignoreCount} does not change.</li>
     * </ul>
     * When not {@link #isModifiable() modifiable}, {@link IllegalStateException} is thrown.
     *
     * @param ignoreCount number of breakpoint activations to ignore before it hits
     *
     * @since 0.9
     */
    public void setIgnoreCount(int ignoreCount) {
        this.ignoreCount = ignoreCount;
    }

    /**
     * @return the number of times this breakpoint has suspended execution
     *
     * @since 0.9
     */
    public int getHitCount() {
        return (int) hitCount.get();
    }

    /**
     * @return a description of this breakpoint's specified location
     *
     * @since 0.9
     */
    public String getLocationDescription() {
        return locationKey.toString();
    }

    /**
     * Test whether this breakpoint can be modified. When <code>false</code>, methods that change
     * breakpoint state throw {@link IllegalStateException}.
     * <p>
     * Unmodifiable breakpoints are created from installed breakpoints as read-only copies to be
     * available to clients other than the one who installed the original breakpoint.
     * {@link Debugger#getBreakpoints()} returns unmodifiable breakpoints, for instance.
     *
     * @return whether this breakpoint can be modified.
     * @since 0.27
     */
    public boolean isModifiable() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * @since 0.9
     */
    @Override
    public String toString() {
        return getClass().getSimpleName() + "@" + Integer.toHexString(hashCode());
    }

    DebuggerNode lookupNode(EventContext context) {
        if (!isEnabled()) {
            return null;
        } else {
            EventBinding<? extends ExecutionEventNodeFactory> binding = breakpointBinding;
            if (binding != null) {
                return (DebuggerNode) context.lookupExecutionEventNode(binding);
            }
            return null;
        }
    }

    private synchronized Assumption getConditionUnchanged() {
        if (conditionUnchanged == null) {
            conditionUnchanged = Truffle.getRuntime().createAssumption("Breakpoint condition unchanged.");
        }
        return conditionUnchanged;
    }

    synchronized void installGlobal(Debugger d) {
        if (disposed) {
            throw new IllegalArgumentException("Cannot install breakpoint, it is disposed already.");
        }
        if (this.debugger != null) {
            throw new IllegalStateException("Breakpoint is already installed in a Debugger instance.");
        }
        install(d);
        this.global = true;
    }

    private void install(Debugger d) {
        assert Thread.holdsLock(this);
        if (this.debugger != null && this.debugger != d) {
            throw new IllegalStateException("Breakpoint is already installed in a different Debugger instance.");
        }
        this.debugger = d;
    }

    synchronized boolean install(DebuggerSession d, boolean failOnError) {
        if (disposed) {
            if (failOnError) {
                throw new IllegalArgumentException("Cannot install breakpoint, it is disposed already.");
            } else {
                return false;
            }
        }
        if (this.sessions.contains(d)) {
            if (failOnError) {
                throw new IllegalStateException("Breakpoint is already installed in the session.");
            } else {
                return true;
            }
        }
        install(d.getDebugger());
        this.sessions.add(d);
        sessionsAssumptionInvalidate();
        if (enabled) {
            install();
        }
        return true;
    }

    private void install() {
        assert Thread.holdsLock(this);
        if (breakpointBinding == null) {
            sourceBinding = debugger.getInstrumenter().attachLoadSourceSectionListener(filter, new LoadSourceSectionListener() {
                public void onLoad(LoadSourceSectionEvent event) {
                    resolveBreakpoint();
                }
            }, true);
            breakpointBinding = debugger.getInstrumenter().attachFactory(filter, new BreakpointNodeFactory());
        }
    }

    boolean isGlobal() {
        return global;
    }

    synchronized void sessionClosed(DebuggerSession d) {
        this.sessions.remove(d);
        sessionsAssumptionInvalidate();
        if (this.sessions.isEmpty()) {
            uninstall();
        }
    }

    private void sessionsAssumptionInvalidate() {
        assert Thread.holdsLock(this);
        Assumption assumption = sessionsUnchanged;
        if (assumption != null) {
            this.sessionsUnchanged = null;
            assumption.invalidate();
        }
    }

    private synchronized void resolveBreakpoint() {
        if (disposed) {
            // cannot resolve disposed breakpoint
            return;
        }
        if (!isResolved()) {
            if (sourceBinding != null) {
                sourceBinding.dispose();
                sourceBinding = null;
            }
            resolved = true;
        }
    }

    private void uninstall() {
        assert Thread.holdsLock(this);
        if (breakpointBinding != null) {
            breakpointBinding.dispose();
            breakpointBinding = null;
        }
        resolved = false;
    }

    /**
     * Returns <code>true</code> if it should appear in the breakpoints list.
     *
     * @throws BreakpointConditionFailure
     */
    boolean notifyIndirectHit(DebuggerNode source, DebuggerNode node, Frame frame) throws BreakpointConditionFailure {
        if (!isEnabled()) {
            return false;
        }
        assert node.getBreakpoint() == this;

        if (source != node) {
            if (!((BreakpointNode) node).shouldBreak(frame)) {
                return false;
            }
        } else {
            // don't do the assert here, the breakpoint condition might have side effects.
            // assert ((BreakpointNode) node).shouldBreak(frame);
        }

        if (this.hitCount.incrementAndGet() <= ignoreCount) {
            // breakpoint hit was ignored
            return false;
        }

        if (isOneShot()) {
            setEnabled(false);
        }
        return true;
    }

    @TruffleBoundary
    @SuppressWarnings("hiding") // We want to mask "sessions", as we recieve preferred ones
    private void doBreak(DebuggerNode source, DebuggerSession[] sessions, MaterializedFrame frame, BreakpointConditionFailure failure) {
        if (!isEnabled()) {
            // make sure we do not cause break events if we got disabled already
            // the instrumentation framework will make sure that this is not happening if the
            // binding was disposed.
            return;
        }
        for (DebuggerSession session : sessions) {
            if (session.isBreakpointsActive()) {
                session.notifyCallback(source, frame, null, failure);
            }
        }
    }

    Breakpoint getROWrapper() {
        assert global;  // wrappers are for global breakpoints only
        GlobalBreakpoint wrapper = roWrapper;
        if (wrapper == null) {
            synchronized (this) {
                wrapper = roWrapper;
                if (wrapper == null) {
                    roWrapper = wrapper = new GlobalBreakpoint(this);
                }
            }
        }
        return wrapper;
    }

    /**
     * Creates a new breakpoint builder based on a URI location.
     *
     * @param sourceUri a URI to specify breakpoint location
     *
     * @since 0.17
     */
    public static Builder newBuilder(URI sourceUri) {
        return BUILDER_INSTANCE.new Builder(sourceUri);
    }

    /**
     * Creates a new breakpoint builder based on a {@link Source}.
     *
     * @param source a {@link Source} to specify breakpoint location
     *
     * @since 0.17
     */
    public static Builder newBuilder(Source source) {
        return BUILDER_INSTANCE.new Builder(source);
    }

    /**
     * Creates a new breakpoint builder based on the textual region of a guest language syntactic
     * component.
     *
     * @param sourceSection a specification for guest language syntactic component
     *
     * @since 0.17
     */
    public static Builder newBuilder(SourceSection sourceSection) {
        return BUILDER_INSTANCE.new Builder(sourceSection);
    }

    /**
     * Builder implementation for a new {@link Breakpoint breakpoint}.
     *
     * @see Breakpoint#newBuilder(Source)
     * @see Breakpoint#newBuilder(URI)
     * @see Breakpoint#newBuilder(SourceSection)
     *
     * @since 0.17
     */
    public final class Builder {

        private final Object key;

        private int line = -1;
        private int ignoreCount;
        private boolean oneShot;
        private SourceSection sourceSection;

        private Builder(Object key) {
            Objects.requireNonNull(key);
            this.key = key;
        }

        private Builder(SourceSection key) {
            this(key.getSource());
            Objects.requireNonNull(key);
            sourceSection = key;
        }

        /**
         * Specifies the breakpoint's line number.
         *
         * Can only be invoked once per builder. Cannot be used together with
         * {@link Breakpoint#newBuilder(SourceSection)}.
         *
         * @param line 1-based line number
         * @throws IllegalStateException if {@code line < 1}
         *
         * @since 0.17
         */
        public Builder lineIs(@SuppressWarnings("hiding") int line) {
            if (line <= 0) {
                throw new IllegalArgumentException("Line argument must be > 0.");
            }
            if (this.line != -1) {
                throw new IllegalStateException("LineIs can only be called once per breakpoint builder.");
            }
            if (sourceSection != null) {
                throw new IllegalArgumentException("LineIs cannot be used with source section based breakpoint. ");
            }
            this.line = line;
            return this;
        }

        /**
         * Specifies the number of times a breakpoint is ignored until it hits (i.e. suspends
         * execution}.
         *
         * @see Breakpoint#setIgnoreCount(int)
         *
         * @since 0.17
         */
        public Builder ignoreCount(@SuppressWarnings("hiding") int ignoreCount) {
            if (ignoreCount < 0) {
                throw new IllegalArgumentException("IgnoreCount argument must be >= 0.");
            }
            this.ignoreCount = ignoreCount;
            return this;
        }

        /**
         * Specifies that the breakpoint will {@linkplain Breakpoint#setEnabled(boolean) disable}
         * itself after suspending execution, i.e. on first hit.
         * <p>
         * Disabled one-shot breakpoints can be {@linkplain Breakpoint#setEnabled(boolean)
         * re-enabled}.
         *
         * @since 0.17
         */
        public Builder oneShot() {
            this.oneShot = true;
            return this;
        }

        /**
         * @return a new breakpoint instance
         *
         * @since 0.17
         */
        public Breakpoint build() {
            SourceSectionFilter f = buildFilter();
            BreakpointLocation location = new BreakpointLocation(key, line);
            Breakpoint breakpoint = new Breakpoint(location, f, oneShot);
            breakpoint.setIgnoreCount(ignoreCount);
            return breakpoint;
        }

        private SourceSectionFilter buildFilter() {
            SourceSectionFilter.Builder f = SourceSectionFilter.newBuilder();
            if (key instanceof URI) {
                final URI sourceUri = (URI) key;
                f.sourceIs(new SourcePredicate() {
                    @Override
                    public boolean test(Source s) {
                        URI uri = s.getURI();
                        return sourceUri.equals(uri);
                    }

                    @Override
                    public String toString() {
                        return "URI equals " + sourceUri;
                    }
                });
            } else {
                assert key instanceof Source;
                f.sourceIs((Source) key);
            }
            if (line != -1) {
                f.lineStartsIn(IndexRange.byLength(line, 1));
            }
            if (sourceSection != null) {
                f.sourceSectionEquals(sourceSection);
            }
            f.tagIs(StatementTag.class);
            return f.build();
        }
    }

    private class BreakpointNodeFactory implements ExecutionEventNodeFactory {

        @Override
        public ExecutionEventNode create(EventContext context) {
            if (!isResolved()) {
                resolveBreakpoint();
            }
            return new BreakpointNode(Breakpoint.this, context);
        }

    }

    private static class BreakpointNode extends DebuggerNode {

        private final Breakpoint breakpoint;
        private final BranchProfile breakBranch = BranchProfile.create();

        @Child private ConditionalBreakNode breakCondition;
        @CompilationFinal(dimensions = 1) private DebuggerSession[] sessions;
        @CompilationFinal private Assumption sessionsUnchanged;

        BreakpointNode(Breakpoint breakpoint, EventContext context) {
            super(context);
            this.breakpoint = breakpoint;
            initializeSessions();
            if (breakpoint.condition != null) {
                this.breakCondition = new ConditionalBreakNode(context, breakpoint);
            }
        }

        private void initializeSessions() {
            CompilerAsserts.neverPartOfCompilation();
            synchronized (breakpoint) {
                this.sessions = breakpoint.sessions.toArray(new DebuggerSession[]{});
                sessionsUnchanged = Truffle.getRuntime().createAssumption("Breakpoint sessions unchanged.");
                breakpoint.sessionsUnchanged = sessionsUnchanged;
            }
        }

        @Override
        SteppingLocation getSteppingLocation() {
            return SteppingLocation.BEFORE_STATEMENT;
        }

        @Override
        boolean isStepNode() {
            return false;
        }

        @Override
        Breakpoint getBreakpoint() {
            return breakpoint;
        }

        @Override
        EventBinding<?> getBinding() {
            return breakpoint.breakpointBinding;
        }

        @Override
        @ExplodeLoop
        protected void onEnter(VirtualFrame frame) {
            if (!sessionsUnchanged.isValid()) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                initializeSessions();
            }
            boolean active = false;
            for (DebuggerSession session : sessions) {
                if (session.isBreakpointsActive()) {
                    active = true;
                    break;
                }
            }
            if (!active) {
                return;
            }
            BreakpointConditionFailure conditionError = null;
            try {
                if (!shouldBreak(frame)) {
                    return;
                }
            } catch (BreakpointConditionFailure e) {
                conditionError = e;
            }
            breakBranch.enter();
            breakpoint.doBreak(this, sessions, frame.materialize(), conditionError);
        }

        boolean shouldBreak(@SuppressWarnings("unused") Frame frame) throws BreakpointConditionFailure {
            // TODO we should use the current frame to evaluate the break condition
            // currently the called break condition needs to access the parent frame
            // using stack access methods.
            if (breakCondition != null) {
                try {
                    return breakCondition.shouldBreak();
                } catch (Throwable e) {
                    CompilerDirectives.transferToInterpreter();
                    throw new BreakpointConditionFailure(breakpoint, e);
                    // fallthrough to true
                }
            }
            return true;
        }

    }

    static final class BreakpointConditionFailure extends SlowPathException {

        private static final long serialVersionUID = 1L;

        private final Breakpoint breakpoint;

        BreakpointConditionFailure(Breakpoint breakpoint, Throwable cause) {
            super(cause);
            this.breakpoint = breakpoint;
        }

        public Breakpoint getBreakpoint() {
            return breakpoint;
        }

        public Throwable getConditionFailure() {
            return getCause();
        }

    }

    private static class ConditionalBreakNode extends Node {

        private static final Object[] EMPTY_ARRAY = new Object[0];

        private final EventContext context;
        private final Breakpoint breakpoint;
        @Child private DirectCallNode conditionCallNode;
        @CompilationFinal private Assumption conditionUnchanged;

        ConditionalBreakNode(EventContext context, Breakpoint breakpoint) {
            this.context = context;
            this.breakpoint = breakpoint;
            this.conditionUnchanged = breakpoint.getConditionUnchanged();
        }

        boolean shouldBreak() {
            if (conditionCallNode == null || !conditionUnchanged.isValid()) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                initializeConditional();
            }
            Object result = conditionCallNode.call(EMPTY_ARRAY);
            if (!(result instanceof Boolean)) {
                CompilerDirectives.transferToInterpreter();
                throw new IllegalArgumentException("Unsupported return type " + result + " in condition.");
            }
            return (Boolean) result;
        }

        private void initializeConditional() {
            Node instrumentedNode = context.getInstrumentedNode();
            final RootNode rootNode = instrumentedNode.getRootNode();
            if (rootNode == null) {
                throw new IllegalStateException("Probe was disconnected from the AST.");
            }

            Source conditionSource;
            synchronized (breakpoint) {
                conditionSource = Source.newBuilder(breakpoint.condition).mimeType(context.getInstrumentedSourceSection().getSource().getMimeType()).name(
                                "breakpoint condition").build();
                if (conditionSource == null) {
                    throw new IllegalStateException("Condition is not resolved " + rootNode);
                }
                conditionUnchanged = breakpoint.getConditionUnchanged();
            }

            final CallTarget callTarget = Debugger.ACCESSOR.parse(conditionSource, instrumentedNode, new String[0]);
            conditionCallNode = insert(Truffle.getRuntime().createDirectCallNode(callTarget));
        }
    }

    /**
     * A read-only wrapper over "global" breakpoint installed on {@link Debugger}. Instances of this
     * wrapper are for public access to global breakpoints.
     */
    @SuppressWarnings("sync-override")
    static final class GlobalBreakpoint extends Breakpoint {

        private final Breakpoint delegate;

        GlobalBreakpoint(Breakpoint delegate) {
            this.delegate = delegate;
        }

        @Override
        public void dispose() {
            fail();
        }

        @Override
        public void setCondition(String expression) {
            fail();
        }

        @Override
        public void setEnabled(boolean enabled) {
            fail();
        }

        @Override
        public void setIgnoreCount(int ignoreCount) {
            fail();
        }

        private static void fail() {
            throw new IllegalStateException("Unmodifiable breakpoint.");
        }

        @Override
        public boolean isModifiable() {
            return false;
        }

        @Override
        public String getCondition() {
            return delegate.getCondition();
        }

        @Override
        public int getHitCount() {
            return delegate.getHitCount();
        }

        @Override
        public int getIgnoreCount() {
            return delegate.getIgnoreCount();
        }

        @Override
        public String getLocationDescription() {
            return delegate.getLocationDescription();
        }

        @Override
        public boolean isDisposed() {
            return delegate.isDisposed();
        }

        @Override
        public boolean isEnabled() {
            return delegate.isEnabled();
        }

        @Override
        public boolean isOneShot() {
            return delegate.isOneShot();
        }

        @Override
        public boolean isResolved() {
            return delegate.isResolved();
        }
    }
}

class BreakpointSnippets {

    public void example() {
        PolyglotEngine engine = PolyglotEngine.newBuilder().build();
        SuspendedCallback suspendedCallback = new SuspendedCallback() {
            public void onSuspend(SuspendedEvent event) {
            }
        };
        Source someCode = Source.newBuilder("").mimeType("").name("").build();

        // @formatter:off
        // BEGIN: BreakpointSnippets.example
        try (DebuggerSession session = Debugger.find(engine).
                        startSession(suspendedCallback)) {

            // install breakpoint in someCode at line 3.
            session.install(Breakpoint.newBuilder(someCode).
                            lineIs(3).build());

            // install breakpoint for a URI at line 3
            session.install(Breakpoint.newBuilder(someCode.getURI()).
                            lineIs(3).build());

        }
        // END: BreakpointSnippets.example
        // @formatter:on

    }
}
