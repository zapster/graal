/*
 * Copyright (c) 2020, Oracle and/or its affiliates.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.oracle.truffle.llvm.tests.interop;

import com.oracle.truffle.llvm.runtime.options.SulongEngineOption;
import com.oracle.truffle.llvm.tests.BaseSuiteHarness;
import com.oracle.truffle.llvm.tests.CLang;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;

import com.oracle.truffle.tck.TruffleRunner;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;

@RunWith(TruffleRunner.class)
public class CLangTest {

    private static Value testCppLibrary;

    private static final String SRC = "\n" +
                    "#include <stdio.h>\n" + //
                    "#include <stdlib.h>\n" + //
                    "#include <polyglot.h>\n" + //
                    "\n" + //
                    "void hello() {\n" + //
                    "  printf(\"hello() is being called in testfile membersTest.cc\");\n" + //
                    "}\n" + //
                    "\n" + //
                    "void bye() {\n" + //
                    "  printf(\"bye() is being called in testfile membersTest.cc\");\n" + //
                    "}\n" + //
                    "    int gcd(int a, int b) {\n" + //
                    "    if (a == 0) {\n" + //
                    "      return b;\n" + //
                    "    } else if (b == 0) {\n" + //
                    "      return a;\n" + //
                    "    } else if (a < 0) {\n" + //
                    "      return gcd(-a, b);\n" + //
                    "    } else if (b < 0) {\n" + //
                    "      return gcd(a, -b);\n" + //
                    "    } else {\n" + //
                    "      return gcd(b, a % b);\n" + //
                    "    }\n" + //
                    "}\n"; //


    @ClassRule
    public static TruffleRunner.RunWithPolyglotRule runWithPolyglot = new TruffleRunner.RunWithPolyglotRule(getContextBuilder());

    public static Context.Builder getContextBuilder() {
        return Context.newBuilder().allowAllAccess(true).allowExperimentalOptions(true).option(SulongEngineOption.CXX_INTEROP_NAME, "true");
    }
    protected static Value loadTestBitcodeValue(String name) {
        org.graalvm.polyglot.Source source;
        try {
            source = org.graalvm.polyglot.Source.newBuilder("clang", SRC, name).mimeType(CLang.MIME_TYPE_CXX).build();
        } catch (IOException ex) {
            throw new AssertionError(ex);
        }
        return runWithPolyglot.getPolyglotContext().eval(source);
    }

    @BeforeClass
    public static void loadTestBitcode() {
        testCppLibrary = loadTestBitcodeValue("membersTest.cpp");
    }

    @Test
    public void testMemberExists() {
        Assert.assertTrue(testCppLibrary.hasMember("hello"));
        Assert.assertTrue(testCppLibrary.hasMember("bye"));
        Assert.assertTrue(testCppLibrary.hasMember("gcd"));
    }

    @Test
    public void testMemberDoesNotExist() {
        Assert.assertFalse(testCppLibrary.hasMember("abc"));
    }
}
