/*
 * Copyright (c) 2017, 2017, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.compiler.truffle.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import org.graalvm.polyglot.Context;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.AllocationEvent;
import com.oracle.truffle.api.instrumentation.AllocationEventFilter;
import com.oracle.truffle.api.instrumentation.AllocationListener;
import com.oracle.truffle.api.instrumentation.AllocationReporter;
import com.oracle.truffle.api.instrumentation.EventBinding;
import com.oracle.truffle.api.instrumentation.TruffleInstrument;
import com.oracle.truffle.api.nodes.LanguageInfo;
import com.oracle.truffle.api.nodes.RootNode;

import org.graalvm.compiler.truffle.GraalTruffleRuntime;
import org.graalvm.compiler.truffle.OptimizedCallTarget;

/**
 * Test of consistent behavior of AllocationReporter when individual calls are optimized or
 * deoptimized.
 */
public class AllocationReporterPartialEvaluationTest extends TestWithSynchronousCompiling {

    private static final GraalTruffleRuntime runtime = (GraalTruffleRuntime) Truffle.getRuntime();

    @Test
    public void testConsistentAssertions() {
        // Test that onEnter()/onReturnValue() are not broken
        // when only one of them is compiled with PE.
        Context context = Context.newBuilder(AllocationReporterLanguage.ID).build();
        context.initialize(AllocationReporterLanguage.ID);
        final TestAllocationReporter tester = context.getEngine().getInstruments().get(TestAllocationReporter.ID).lookup(TestAllocationReporter.class);
        assertNotNull(tester);
        final AllocationReporter reporter = (AllocationReporter) context.importSymbol(AllocationReporter.class.getSimpleName()).asHostObject();
        final Long[] value = new Long[]{1L};
        OptimizedCallTarget enterTarget = (OptimizedCallTarget) runtime.createCallTarget(new RootNode(null) {
            @Override
            public Object execute(VirtualFrame frame) {
                reporter.onEnter(value[0], 4, 8);
                return null;
            }
        });
        OptimizedCallTarget returnTarget = (OptimizedCallTarget) runtime.createCallTarget(new RootNode(null) {
            @Override
            public Object execute(VirtualFrame frame) {
                reporter.onReturnValue(value[0], 4, 8);
                return null;
            }
        });

        // Interpret both:
        assertNotCompiled(enterTarget);
        enterTarget.call();
        assertNotCompiled(returnTarget);
        returnTarget.call();
        value[0]++;
        enterTarget.compile();
        returnTarget.compile();
        assertCompiled(enterTarget);
        assertCompiled(returnTarget);
        long expectedCounters = allocCounter(value[0]);
        assertEquals(expectedCounters, tester.getEnterCount());
        assertEquals(expectedCounters, tester.getReturnCount());

        for (int j = 0; j < 2; j++) {
            // Compile both:
            for (int i = 0; i < 5; i++) {
                assertCompiled(enterTarget);
                enterTarget.call();
                assertCompiled(returnTarget);
                returnTarget.call();
                value[0]++;
            }
            expectedCounters = allocCounter(value[0]);
            assertEquals(expectedCounters, tester.getEnterCount());
            assertEquals(expectedCounters, tester.getReturnCount());

            // Deoptimize enter:
            enterTarget.invalidate();
            assertNotCompiled(enterTarget);
            enterTarget.call();
            assertCompiled(returnTarget);
            returnTarget.call();
            value[0]++;
            enterTarget.compile();
            returnTarget.compile();
            assertCompiled(enterTarget);
            assertCompiled(returnTarget);

            // Deoptimize return:
            returnTarget.invalidate();
            assertCompiled(enterTarget);
            enterTarget.call();
            assertNotCompiled(returnTarget);
            returnTarget.call();
            value[0]++;
            enterTarget.compile();
            returnTarget.compile();
            assertCompiled(enterTarget);
            assertCompiled(returnTarget);

            // Deoptimize both:
            enterTarget.invalidate();
            returnTarget.invalidate();
            assertNotCompiled(enterTarget);
            enterTarget.call();
            assertNotCompiled(returnTarget);
            returnTarget.call();
            value[0]++;
            enterTarget.compile();
            returnTarget.compile();
            assertCompiled(enterTarget);
            assertCompiled(returnTarget);
        }
        // Check that the allocation calls happened:
        expectedCounters = allocCounter(value[0]);
        assertEquals(expectedCounters, tester.getEnterCount());
        assertEquals(expectedCounters, tester.getReturnCount());
        assertCompiled(enterTarget);
        assertCompiled(returnTarget);

        // Verify that the assertions work in the compiled code:
        value[0] = null;
        boolean expectedFailure = true;
        // Deoptimize for assertions to be active
        enterTarget.invalidate();
        try {
            enterTarget.call();
            expectedFailure = false;
        } catch (AssertionError err) {
            // O.K.
        }
        assertTrue("onEnter(null) did not fail!", expectedFailure);

        // Deoptimize for assertions to be active
        returnTarget.invalidate();

        value[0] = Long.MIN_VALUE;
        try {
            returnTarget.call();
            expectedFailure = false;
        } catch (AssertionError err) {
            // O.K.
        }
        assertTrue("onReturn(<unseen value>) did not fail!", expectedFailure);

    }

    private static long allocCounter(long n) {
        return n * (n - 1) / 2;
    }

    @TruffleLanguage.Registration(mimeType = AllocationReporterLanguage.MIME_TYPE, name = "Allocation Reporter PE Test Language", id = AllocationReporterLanguage.ID, version = "1.0")
    public static class AllocationReporterLanguage extends TruffleLanguage<AllocationReporter> {

        static final String ID = "truffle-allocation-reporter-pe-test-language";
        static final String MIME_TYPE = "application/x-truffle-allocation-reporter-pe-test-language";

        @Override
        protected AllocationReporter createContext(TruffleLanguage.Env env) {
            return env.lookup(AllocationReporter.class);
        }

        @Override
        protected Object findExportedSymbol(AllocationReporter context, String globalName, boolean onlyExplicit) {
            if (AllocationReporter.class.getSimpleName().equals(globalName)) {
                return context;
            }
            return null;
        }

        @Override
        protected Object getLanguageGlobal(AllocationReporter context) {
            return null;
        }

        @Override
        protected boolean isObjectOfLanguage(Object object) {
            return false;
        }

    }

    @TruffleInstrument.Registration(id = TestAllocationReporter.ID, services = TestAllocationReporter.class)
    public static class TestAllocationReporter extends TruffleInstrument implements AllocationListener {

        static final String ID = "testAllocationReporterPE";

        private EventBinding<TestAllocationReporter> allocationEventBinding;
        private long enterCounter = 0;
        private long returnCounter = 0;

        @Override
        protected void onCreate(TruffleInstrument.Env env) {
            env.registerService(this);
            LanguageInfo testLanguage = env.getLanguages().get(AllocationReporterLanguage.ID);
            allocationEventBinding = env.getInstrumenter().attachAllocationListener(AllocationEventFilter.newBuilder().languages(testLanguage).build(), this);
        }

        @Override
        protected void onDispose(TruffleInstrument.Env env) {
            allocationEventBinding.dispose();
        }

        EventBinding<TestAllocationReporter> getAllocationEventBinding() {
            return allocationEventBinding;
        }

        long getEnterCount() {
            return enterCounter;
        }

        long getReturnCount() {
            return returnCounter;
        }

        @Override
        public void onEnter(AllocationEvent event) {
            enterCounter += (Long) event.getValue();
        }

        @Override
        public void onReturnValue(AllocationEvent event) {
            returnCounter += (Long) event.getValue();
        }

    }

}
