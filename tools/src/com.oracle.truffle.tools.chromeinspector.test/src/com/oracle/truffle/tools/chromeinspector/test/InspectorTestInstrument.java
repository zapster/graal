/*
 * Copyright (c) 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.tools.chromeinspector.test;

import java.io.PrintWriter;

import com.oracle.truffle.api.instrumentation.TruffleInstrument;
import com.oracle.truffle.api.test.ReflectionUtils;

import com.oracle.truffle.tools.chromeinspector.TruffleDebugger;
import com.oracle.truffle.tools.chromeinspector.TruffleExecutionContext;
import com.oracle.truffle.tools.chromeinspector.TruffleProfiler;
import com.oracle.truffle.tools.chromeinspector.TruffleRuntime;
import com.oracle.truffle.tools.chromeinspector.server.ConnectionWatcher;
import com.oracle.truffle.tools.chromeinspector.server.InspectServerSession;

@TruffleInstrument.Registration(id = InspectorTestInstrument.ID, services = InspectSessionInfoProvider.class)
public final class InspectorTestInstrument extends TruffleInstrument {

    public static final String ID = "InspectorTestInstrument";

    @Override
    protected void onCreate(final Env env) {
        env.registerService(new InspectSessionInfoProvider() {
            @Override
            public InspectSessionInfo getSessionInfo(final boolean suspend, final boolean inspectInternal, final boolean inspectInitialization) {
                return new InspectSessionInfo() {

                    private InspectServerSession iss;
                    private ConnectionWatcher connectionWatcher;
                    private long id;

                    InspectSessionInfo init() {
                        TruffleExecutionContext context = new TruffleExecutionContext("test", inspectInternal, inspectInitialization, env, new PrintWriter(env.err()));
                        this.connectionWatcher = new ConnectionWatcher();
                        TruffleRuntime runtime = new TruffleRuntime(context);
                        TruffleDebugger debugger = new TruffleDebugger(context, suspend);
                        TruffleProfiler profiler = new TruffleProfiler(context, connectionWatcher);
                        this.iss = new InspectServerSession(runtime, debugger, profiler, context);
                        this.id = context.getId();
                        // Fake connection open
                        ReflectionUtils.invoke(connectionWatcher, "notifyOpen");
                        return this;
                    }

                    @Override
                    public InspectServerSession getInspectServerSession() {
                        return iss;
                    }

                    @Override
                    public ConnectionWatcher getConnectionWatcher() {
                        return connectionWatcher;
                    }

                    @Override
                    public long getId() {
                        return id;
                    }
                }.init();
            }
        });
    }

}

interface InspectSessionInfoProvider {
    InspectSessionInfo getSessionInfo(boolean suspend, boolean inspectInternal, boolean inspectInitialization);
}

interface InspectSessionInfo {
    InspectServerSession getInspectServerSession();

    ConnectionWatcher getConnectionWatcher();

    long getId();
}
