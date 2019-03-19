package org.graalvm.compiler.lir.jtt.saraverify.faultinjection;

import org.graalvm.compiler.debug.GraalError;
import org.junit.Rule;

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
import org.junit.rules.ExpectedException;

/*
 */
public class BC_iadd3 extends InjectorTest {

    private final String UNDEFINED_REGISTER_ERROR_MSG = "Used register rax is not defined.";

    @Override
    public Injector getInjectorPhase() {
        Injector injector = new Injector();
        return injector.new SelfCopyInjector();
    }

    public static int test(short a, short b) {
        return a + b;
    }

    @Rule public ExpectedException thrown = ExpectedException.none();

    @Test
    public void run0() throws Throwable {
        thrown.expect(GraalError.class);
        thrown.expectMessage(UNDEFINED_REGISTER_ERROR_MSG);
        runTest("test", ((short) 1), ((short) 2));
    }

    @Test
    public void run1() throws Throwable {
        thrown.expect(GraalError.class);
        thrown.expectMessage(UNDEFINED_REGISTER_ERROR_MSG);
        runTest("test", ((short) 0), ((short) -1));
    }

    @Test
    public void run2() throws Throwable {
        thrown.expect(GraalError.class);
        thrown.expectMessage(UNDEFINED_REGISTER_ERROR_MSG);
        runTest("test", ((short) 33), ((short) 67));
    }

    @Test
    public void run3() throws Throwable {
        thrown.expect(GraalError.class);
        thrown.expectMessage(UNDEFINED_REGISTER_ERROR_MSG);
        runTest("test", ((short) 1), ((short) -1));
    }

    @Test
    public void run4() throws Throwable {
        thrown.expect(GraalError.class);
        thrown.expectMessage(UNDEFINED_REGISTER_ERROR_MSG);
        runTest("test", ((short) -128), ((short) 1));
    }

    @Test
    public void run5() throws Throwable {
        thrown.expect(GraalError.class);
        thrown.expectMessage(UNDEFINED_REGISTER_ERROR_MSG);
        runTest("test", ((short) 127), ((short) 1));
    }

    @Test
    public void run6() throws Throwable {
        thrown.expect(GraalError.class);
        thrown.expectMessage(UNDEFINED_REGISTER_ERROR_MSG);
        runTest("test", ((short) -32768), ((short) 1));
    }

    @Test
    public void run7() throws Throwable {
        thrown.expect(GraalError.class);
        thrown.expectMessage(UNDEFINED_REGISTER_ERROR_MSG);
        runTest("test", ((short) 32767), ((short) 1));
    }

}
