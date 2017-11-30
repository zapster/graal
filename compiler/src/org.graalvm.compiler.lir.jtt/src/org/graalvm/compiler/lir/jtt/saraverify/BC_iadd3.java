package org.graalvm.compiler.lir.jtt.saraverify;

import java.util.ListIterator;

import org.graalvm.compiler.jtt.JTTTest;
import org.graalvm.compiler.lir.phases.LIRPhase;
import org.graalvm.compiler.lir.phases.LIRSuites;
import org.graalvm.compiler.lir.alloc.lsra.LinearScanPhase;
import org.graalvm.compiler.lir.dfa.MarkBasePointersPhase;
import org.graalvm.compiler.lir.phases.AllocationPhase.AllocationContext;
import org.graalvm.compiler.lir.saraverify.RegisterAllocationVerificationPhase;
import org.graalvm.compiler.lir.saraverify.VerificationPhase;
import org.graalvm.compiler.options.OptionValues;

/*
 * Copyright (c) 2007, 2012, Oracle and/or its affiliates. All rights reserved.
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
/*
 * Copyright (c) 2007, 2012, Oracle and/or its affiliates. All rights reserved.
 */

import org.junit.Test;

/*
 */
public class BC_iadd3 extends JTTTest {

    @Override
    protected LIRSuites createLIRSuites(OptionValues opts) {
        LIRSuites lirSuites = super.createLIRSuites(opts);
        RegisterAllocationVerificationPhase registerAllocationVerification = new RegisterAllocationVerificationPhase();
        VerificationPhase verification = new VerificationPhase();

        ListIterator<LIRPhase<AllocationContext>> phase = lirSuites.getAllocationStage().findPhase(MarkBasePointersPhase.class);
        assert phase != null;
        phase.add(registerAllocationVerification);

        phase = lirSuites.getAllocationStage().findPhase(LinearScanPhase.class);
        assert phase != null;
        phase.add(verification);

        return lirSuites;
    }

    public static int test(short a, short b) {
        return a + b;
    }

    @Test
    public void run0() throws Throwable {
        runTest("test", ((short) 1), ((short) 2));
    }

    @Test
    public void run1() throws Throwable {
        runTest("test", ((short) 0), ((short) -1));
    }

    @Test
    public void run2() throws Throwable {
        runTest("test", ((short) 33), ((short) 67));
    }

    @Test
    public void run3() throws Throwable {
        runTest("test", ((short) 1), ((short) -1));
    }

    @Test
    public void run4() throws Throwable {
        runTest("test", ((short) -128), ((short) 1));
    }

    @Test
    public void run5() throws Throwable {
        runTest("test", ((short) 127), ((short) 1));
    }

    @Test
    public void run6() throws Throwable {
        runTest("test", ((short) -32768), ((short) 1));
    }

    @Test
    public void run7() throws Throwable {
        runTest("test", ((short) 32767), ((short) 1));
    }

}
