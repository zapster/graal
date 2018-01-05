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
package com.oracle.truffle.tools.chromeinspector.instrument;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;

import org.graalvm.options.OptionCategory;
import org.graalvm.options.OptionDescriptors;
import org.graalvm.options.OptionKey;
import org.graalvm.options.OptionType;
import org.graalvm.options.OptionValues;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleContext;
import com.oracle.truffle.api.instrumentation.ContextsListener;
import com.oracle.truffle.api.instrumentation.EventBinding;
import com.oracle.truffle.api.instrumentation.TruffleInstrument;
import com.oracle.truffle.api.nodes.LanguageInfo;

import com.oracle.truffle.tools.chromeinspector.TruffleExecutionContext;
import com.oracle.truffle.tools.chromeinspector.server.WebSocketServer;

/**
 * Chrome inspector as an instrument.
 */
@TruffleInstrument.Registration(id = InspectorInstrument.INSTRUMENT_ID, name = "Chrome Inspector", version = InspectorInstrument.VERSION)
public final class InspectorInstrument extends TruffleInstrument {

    static final int DEFAULT_PORT = 9229;
    static final OptionType<Integer> PORT_OR_BOOLEAN = new OptionType<>("PortOrBoolean", DEFAULT_PORT, (s) -> {
        if (s.isEmpty() || s.equals("true")) {
            return DEFAULT_PORT;
        } else {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Port is not a number: " + s);
            }
        }
    }, (port) -> {
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Invalid port number: " + port);
        }
    });

    @com.oracle.truffle.api.Option(name = "", help = "Start the Chrome inspector. Default port is " + DEFAULT_PORT, category = OptionCategory.USER) //
    static final OptionKey<Integer> Inspect = new OptionKey<>(DEFAULT_PORT, PORT_OR_BOOLEAN);

    @com.oracle.truffle.api.Option(help = "Suspend the execution at first executed source line. (default:true)", category = OptionCategory.USER) //
    static final OptionKey<Boolean> Suspend = new OptionKey<>(true);

    @com.oracle.truffle.api.Option(help = "Do not execute any source code until inspector client is attached. (default:false)", category = OptionCategory.EXPERT) //
    static final OptionKey<Boolean> WaitAttached = new OptionKey<>(false);

    @com.oracle.truffle.api.Option(help = "Hide internal errors that can occur as a result of debugger inspection. (default:false)", category = OptionCategory.EXPERT) //
    static final OptionKey<Boolean> HideErrors = new OptionKey<>(false);

    @com.oracle.truffle.api.Option(help = "Path to the chrome inspect. (default: randomly generated)", category = OptionCategory.EXPERT) //
    static final OptionKey<String> Path = new OptionKey<>("");

    @com.oracle.truffle.api.Option(help = "Don't use loopback address. (default:false)", category = OptionCategory.USER) //
    static final OptionKey<Boolean> Remote = new OptionKey<>(false);

    public static final String INSTRUMENT_ID = "inspect";
    static final String VERSION = "0.1";

    @Override
    protected void onCreate(Env env) {
        OptionValues options = env.getOptions();
        if (options.hasBeenSet(Inspect)) {
            Server server = new Server(env);
            try {
                server.start("Main Context", options.get(Inspect), options.get(Suspend), options.get(WaitAttached), options.get(HideErrors), options.get(Path), options.get(Remote));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    protected OptionDescriptors getOptionDescriptors() {
        return new InspectorInstrumentOptionDescriptors();
    }

    static final class Server {

        private final Env env;
        private volatile WebSocketServer wss;

        Server(Env env) {
            this.env = env;
        }

        String start(String contextName, int port, boolean debugBreak, boolean waitAttached, boolean hideErrors, String path, boolean remote) throws IOException {
            PrintWriter info = new PrintWriter(env.err());
            String wsspath;
            if (path == null || path.isEmpty()) {
                wsspath = "/" + Long.toHexString(System.identityHashCode(env)) + "-" + Long.toHexString(System.nanoTime() ^ System.identityHashCode(env));
            } else {
                String head = path.startsWith("/") ? "" : "/";
                wsspath = head + path;
            }

            PrintWriter err = (hideErrors) ? null : info;
            final TruffleExecutionContext executionContext = TruffleExecutionContext.create(contextName, env, err);
            if (debugBreak || waitAttached) {
                final EventBinding<?>[] execEnter = new EventBinding<?>[1];
                execEnter[0] = env.getInstrumenter().attachContextsListener(new ContextsListener() {
                    @Override
                    public void onContextCreated(TruffleContext context) {
                    }

                    @Override
                    public void onLanguageContextCreated(TruffleContext context, LanguageInfo language) {
                    }

                    @Override
                    @TruffleBoundary
                    public void onLanguageContextInitialized(TruffleContext context, LanguageInfo language) {
                        try {
                            executionContext.waitForRunPermission();
                        } catch (InterruptedException ex) {
                        }
                        execEnter[0].dispose();
                    }

                    @Override
                    public void onLanguageContextFinalized(TruffleContext context, LanguageInfo language) {
                    }

                    @Override
                    public void onLanguageContextDisposed(TruffleContext context, LanguageInfo language) {
                    }

                    @Override
                    public void onContextClosed(TruffleContext context) {
                    }
                }, true);
            }
            InetAddress ia;
            if (remote) {
                ia = InetAddress.getLocalHost();
            } else {
                ia = InetAddress.getLoopbackAddress();
            }
            InetSocketAddress isa = new InetSocketAddress(ia, port);
            wss = WebSocketServer.get(isa, wsspath, executionContext, debugBreak);
            String address = buildAddress(isa.getAddress().getHostAddress(), wss.getListeningPort(), wsspath);
            info.println("Debugger listening on port " + wss.getListeningPort() + ".");
            info.println("To start debugging, open the following URL in Chrome:");
            info.println("    " + address);
            info.flush();
            return address;
        }

        private static final String ADDRESS_PREFIX = "chrome-devtools://devtools/bundled/inspector.html?experiments=true&v8only=true&ws=";

        private static String buildAddress(String hostAddress, int port, String path) {
            return ADDRESS_PREFIX + hostAddress + ":" + port + path;
        }

        public void close() {
            if (wss != null) {
                wss.stop();
                wss = null;
            }
        }

        public boolean isRunning() {
            return wss != null;
        }
    }
}
