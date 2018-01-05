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
package com.oracle.truffle.tools.chromeinspector;

import java.io.File;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Phaser;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

import com.oracle.truffle.api.TruffleException;
import com.oracle.truffle.api.debug.Breakpoint;
import com.oracle.truffle.api.debug.DebugScope;
import com.oracle.truffle.api.debug.DebugStackFrame;
import com.oracle.truffle.api.debug.DebugValue;
import com.oracle.truffle.api.debug.Debugger;
import com.oracle.truffle.api.debug.DebuggerSession;
import com.oracle.truffle.api.debug.SuspendedCallback;
import com.oracle.truffle.api.debug.SuspendedEvent;
import com.oracle.truffle.api.debug.SuspensionFilter;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;

import com.oracle.truffle.tools.chromeinspector.commands.Params;
import com.oracle.truffle.tools.chromeinspector.domains.DebuggerDomain;
import com.oracle.truffle.tools.chromeinspector.events.Event;
import com.oracle.truffle.tools.chromeinspector.ScriptsHandler.LoadScriptListener;
import com.oracle.truffle.tools.chromeinspector.TruffleExecutionContext.CancellableRunnable;
import com.oracle.truffle.tools.chromeinspector.TruffleExecutionContext.GuestLanguageException;
import com.oracle.truffle.tools.chromeinspector.TruffleExecutionContext.NoSuspendedThreadException;
import com.oracle.truffle.tools.chromeinspector.TruffleExecutionContext.SuspendedThreadExecutor;
import com.oracle.truffle.tools.chromeinspector.server.CommandProcessException;
import com.oracle.truffle.tools.chromeinspector.server.InspectServerSession.CommandPostProcessor;
import com.oracle.truffle.tools.chromeinspector.types.CallArgument;
import com.oracle.truffle.tools.chromeinspector.types.CallFrame;
import com.oracle.truffle.tools.chromeinspector.types.Location;
import com.oracle.truffle.tools.chromeinspector.types.RemoteObject;
import com.oracle.truffle.tools.chromeinspector.types.Scope;
import com.oracle.truffle.tools.chromeinspector.types.Script;

public final class TruffleDebugger extends DebuggerDomain {

    private final TruffleExecutionContext context;
    private final Object suspendLock = new Object();
    private DebuggerSession ds;
    private ScriptsHandler slh;
    private BreakpointsHandler bph;
    // private Scope globalScope;
    private volatile DebuggerSuspendedInfo suspendedInfo; // Set when suspended
    private boolean running = true;
    private final Phaser onSuspendPhaser = new Phaser();
    private final BlockingQueue<CancellableRunnable> suspendThreadExecutables = new LinkedBlockingQueue<>();

    public TruffleDebugger(TruffleExecutionContext context) {
        this(context, false);
    }

    public TruffleDebugger(TruffleExecutionContext context, boolean suspend) {
        this.context = context;
        context.setSuspendThreadExecutor(new SuspendedThreadExecutor() {
            @Override
            public void execute(CancellableRunnable executable) throws NoSuspendedThreadException {
                try {
                    synchronized (suspendLock) {
                        if (running) {
                            NoSuspendedThreadException.raise();
                        }
                        suspendThreadExecutables.put(executable);
                        suspendLock.notifyAll();
                    }
                } catch (InterruptedException iex) {
                    throw new RuntimeException(iex);
                }
            }
        });
        if (suspend) {
            doEnable();
            ds.suspendNextExecution();
        }
    }

    private void doEnable() {
        Debugger tdbg = context.getEnv().lookup(context.getEnv().getInstruments().get("debugger"), Debugger.class);
        ds = tdbg.startSession(new SuspendedCallbackImpl());
        ds.setSteppingFilter(SuspensionFilter.newBuilder().ignoreLanguageContextInitialization(true).includeInternal(false).build());
        slh = context.getScriptsHandler();
        bph = new BreakpointsHandler(ds);
        // globalScope = new Scope("global", null, null, null); // TODO
    }

    @Override
    public void enable() {
        if (ds == null) {
            doEnable();
        }
        slh.addLoadScriptListener(new LoadScriptListenerImpl());
    }

    @Override
    public void disable() {
        if (ds != null) {
            ds.close();
            ds = null;
            context.releaseScriptsHandler();
            slh = null;
            bph = null;
            synchronized (suspendLock) {
                if (!running) {
                    running = true;
                    suspendLock.notifyAll();
                }
            }
        }
    }

    @Override
    public void setAsyncCallStackDepth(int maxDepth) {

    }

    @Override
    public void setBlackboxPatterns(String[] patterns) {
        final Pattern[] compiledPatterns = new Pattern[patterns.length];
        for (int i = 0; i < patterns.length; i++) {
            compiledPatterns[i] = Pattern.compile(patterns[i]);
        }
        ds.setSteppingFilter(SuspensionFilter.newBuilder().ignoreLanguageContextInitialization(true).includeInternal(false).sourceIs(
                        source -> !sourceMatchesBlackboxPatterns(source, compiledPatterns)).build());
    }

    @Override
    public void setPauseOnExceptions(String state) {

    }

    @Override
    public Params getScriptSource(String scriptId) throws CommandProcessException {
        if (scriptId == null) {
            throw new CommandProcessException("A scriptId required.");
        }
        Script script;
        try {
            script = slh.getScript(Integer.parseInt(scriptId));
            if (script == null) {
                throw new CommandProcessException("Unknown scriptId: " + scriptId);
            }
        } catch (NumberFormatException nfe) {
            throw new CommandProcessException(nfe.getMessage());
        }
        JSONObject json = new JSONObject();
        json.put("scriptSource", script.getSource().getCharacters());
        return new Params(json);
    }

    @Override
    public void pause() {
        DebuggerSuspendedInfo susp = suspendedInfo;
        if (susp == null) {
            ds.suspendNextExecution();
        }
    }

    @Override
    public void resume(CommandPostProcessor postProcessor) {
        DebuggerSuspendedInfo susp = suspendedInfo;
        if (susp != null) {
            postProcessor.setPostProcessJob(() -> doResume());
        }
    }

    @Override
    public void stepInto(CommandPostProcessor postProcessor) {
        DebuggerSuspendedInfo susp = suspendedInfo;
        if (susp != null) {
            susp.getSuspendedEvent().prepareStepInto(1);
            postProcessor.setPostProcessJob(() -> doResume());
        }
    }

    @Override
    public void stepOver(CommandPostProcessor postProcessor) {
        DebuggerSuspendedInfo susp = suspendedInfo;
        if (susp != null) {
            susp.getSuspendedEvent().prepareStepOver(1);
            postProcessor.setPostProcessJob(() -> doResume());
        }
    }

    @Override
    public void stepOut(CommandPostProcessor postProcessor) {
        DebuggerSuspendedInfo susp = suspendedInfo;
        if (susp != null) {
            susp.getSuspendedEvent().prepareStepOut(1);
            postProcessor.setPostProcessJob(() -> doResume());
        }
    }

    private void doResume() {
        synchronized (suspendLock) {
            if (!running) {
                running = true;
                suspendLock.notifyAll();
            }
        }
        // Wait for onSuspend() to finish
        try {
            onSuspendPhaser.awaitAdvanceInterruptibly(0);
        } catch (InterruptedException ex) {
        }
    }

    private CallFrame[] createCallFrames(Iterable<DebugStackFrame> frames) {
        List<CallFrame> cfs = new ArrayList<>();
        int depth = 0;
        for (DebugStackFrame frame : frames) {
            SourceSection sourceSection = frame.getSourceSection();
            if (sourceSection == null) {
                continue;
            }
            if (frame.isInternal()) {
                continue;
            }
            Source source = sourceSection.getSource();
            if (source.isInternal()) {
                // should not be, double-check
                continue;
            }
            slh.assureLoaded(source);
            Script script = slh.getScript(slh.getScriptId(source));
            List<Scope> scopes = new ArrayList<>();
            DebugScope dscope;
            try {
                dscope = frame.getScope();
            } catch (Exception ex) {
                PrintWriter err = context.getErr();
                if (err != null) {
                    err.println("getScope() has caused " + ex);
                    ex.printStackTrace(err);
                }
                dscope = null;
            }
            String scopeType = "block";
            boolean wasFunction = false;
            SourceSection functionSourceSection = null;
            while (dscope != null) {
                if (wasFunction) {
                    scopeType = "closure";
                } else if (dscope.isFunctionScope()) {
                    scopeType = "local";
                    functionSourceSection = dscope.getSourceSection();
                    wasFunction = true;
                }
                if (dscope.isFunctionScope() || dscope.getDeclaredValues().iterator().hasNext()) {
                    // provide only scopes that have some variables
                    scopes.add(createScope(scopeType, dscope));
                }
                dscope = getParent(dscope);
            }
            try {
                dscope = ds.getTopScope(source.getLanguage());
            } catch (Exception ex) {
                PrintWriter err = context.getErr();
                if (err != null) {
                    err.println("getTopScope() has caused " + ex);
                    ex.printStackTrace(err);
                }
            }
            while (dscope != null) {
                if (dscope.getDeclaredValues().iterator().hasNext()) {
                    // provide only scopes that have some variables
                    scopes.add(createScope("global", dscope));
                }
                dscope = getParent(dscope);
            }
            CallFrame cf = new CallFrame(frame, depth++, script, sourceSection,
                            functionSourceSection, null, scopes.toArray(new Scope[scopes.size()]));
            cfs.add(cf);
        }
        return cfs.toArray(new CallFrame[cfs.size()]);
    }

    private Scope createScope(String scopeType, DebugScope dscope) {
        RemoteObject scopeVars = new RemoteObject(dscope);
        context.getRemoteObjectsHandler().register(scopeVars);
        return new Scope(scopeType, scopeVars, dscope.getName(), null, null);
    }

    private DebugScope getParent(DebugScope dscope) {
        DebugScope parentScope;
        try {
            parentScope = dscope.getParent();
        } catch (Exception ex) {
            PrintWriter err = context.getErr();
            if (err != null) {
                err.println("Scope.getParent() has caused " + ex);
                ex.printStackTrace(err);
            }
            parentScope = null;
        }
        return parentScope;
    }

    @Override
    public void setBreakpointsActive(Optional<Boolean> active) throws CommandProcessException {
        if (!active.isPresent()) {
            throw new CommandProcessException("Must specify active argument.");
        }
        ds.setBreakpointsActive(active.get());
    }

    @Override
    public Params setBreakpointByUrl(String url, String urlRegex, int line, int column, String condition) throws CommandProcessException {
        if (url == null && urlRegex == null) {
            throw new CommandProcessException("Must specify either url or urlRegex.");
        }
        if (line <= 0) {
            throw new CommandProcessException("Must specify line number.");
        }
        if (url != null) {
            URI uri = getScriptURIForBP(url);
            return bph.createURLBreakpoint(uri, line, column, condition);
        } else {
            // TODO
            throw new CommandProcessException("urlRegex not supported at the moment.");
        }
    }

    @Override
    public Params setBreakpoint(Location location, String condition) throws CommandProcessException {
        if (location == null) {
            throw new CommandProcessException("Must specify location.");
        }
        Script script = slh.getScript(location.getScriptId());
        if (script == null) {
            throw new CommandProcessException("No script with id '" + location.getScriptId() + "'");
        }
        return bph.createBreakpoint(location, script.getSource().getURI(), condition);
    }

    @Override
    public void removeBreakpoint(String id) throws CommandProcessException {
        if (!bph.removeBreakpoint(id)) {
            throw new CommandProcessException("No breakpoint with id '" + id + "'");
        }
    }

    @Override
    public void continueToLocation(Location location, CommandPostProcessor postProcessor) throws CommandProcessException {
        if (location == null) {
            throw new CommandProcessException("Must specify location.");
        }
        Script script = slh.getScript(location.getScriptId());
        if (script == null) {
            throw new CommandProcessException("No script with id '" + location.getScriptId() + "'");
        }
        bph.createOneShotBreakpoint(location, script.getSource().getURI());
        resume(postProcessor);
    }

    @Override
    public Params evaluateOnCallFrame(String callFrameId, String expression, String objectGroup,
                    boolean includeCommandLineAPI, boolean silent, boolean returnByValue,
                    boolean generatePreview, boolean throwOnSideEffect) throws CommandProcessException {
        if (callFrameId == null) {
            throw new CommandProcessException("A callFrameId required.");
        }
        if (expression == null) {
            throw new CommandProcessException("An expression required.");
        }
        int frameId;
        try {
            frameId = Integer.parseInt(callFrameId);
        } catch (NumberFormatException ex) {
            throw new CommandProcessException(ex.getLocalizedMessage());
        }
        JSONObject jsonResult;
        try {
            jsonResult = context.executeInSuspendThread(new SuspendThreadExecutable<JSONObject>() {
                @Override
                public JSONObject executeCommand() throws CommandProcessException {
                    if (frameId >= suspendedInfo.getCallFrames().length) {
                        throw new CommandProcessException("Too big callFrameId: " + frameId);
                    }
                    CallFrame cf = suspendedInfo.getCallFrames()[frameId];
                    JSONObject json = new JSONObject();
                    try {
                        DebugValue value = cf.getFrame().eval(expression);
                        RemoteObject ro = new RemoteObject(value, context.getErr());
                        context.getRemoteObjectsHandler().register(ro);
                        json.put("result", ro.toJSON());
                    } catch (Throwable t) {
                        // TODO: Have a proper abstraction of TruffleException in debugger APIs
                        if (t instanceof TruffleException && !((TruffleException) t).isInternalError()) {
                            JSONObject err = new JSONObject();
                            err.putOpt("value", t.getLocalizedMessage());
                            json.put("result", err);
                        } else {
                            throw t;
                        }
                    }
                    return json;
                }
            });
        } catch (NoSuspendedThreadException e) {
            jsonResult = new JSONObject();
            JSONObject err = new JSONObject();
            err.putOpt("value", e.getLocalizedMessage());
            jsonResult.put("result", err);
        } catch (GuestLanguageException e) {
            jsonResult = new JSONObject();
            TruffleRuntime.fillExceptionDetails(jsonResult, e);
        }
        return new Params(jsonResult);
    }

    @Override
    public void setVariableValue(int scopeNumber, String variableName, CallArgument newValue, String callFrameId) throws CommandProcessException {
        if (variableName == null) {
            throw new CommandProcessException("A variableName required.");
        }
        if (newValue == null) {
            throw new CommandProcessException("A newValue required.");
        }
        if (callFrameId == null) {
            throw new CommandProcessException("A callFrameId required.");
        }
    }

    public static boolean sourceMatchesBlackboxPatterns(Source source, Pattern[] patterns) {
        String uri = ScriptsHandler.getNiceStringFromURI(source.getURI());
        for (Pattern pattern : patterns) {
            // Check whether pattern corresponds to:
            // 1) the name of a file
            if (pattern.pattern().equals(source.getName())) {
                return true;
            }
            // 2) regular expression to target
            Matcher matcher = pattern.matcher(uri);
            if (matcher.matches() || pattern.pattern().endsWith("$") && matcher.find()) {
                return true;
            }
            // 3) an entire folder that contains scripts to blackbox
            String path = source.getPath();
            int idx = path != null ? path.lastIndexOf(File.separatorChar) : -1;
            if (idx > 0) {
                path = path.substring(0, idx);
                if (path.endsWith(File.separator + pattern.pattern())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static URI getScriptURIForBP(String scripturl) throws CommandProcessException {
        int i = 0;
        while (i < scripturl.length()) {
            char c = scripturl.charAt(i);
            if (c == ':') {
                break;
            } else if (c == '/' || c == '?' || c == '#') {
                i = 0;
                break;
            }
            i++;
        }
        URI uri;
        try {
            if (i > 0) {
                // There is a scheme
                uri = ScriptsHandler.getURIFromNiceString(scripturl);
            } else {
                uri = new URI("file", null, scripturl, null, null);
            }
        } catch (URISyntaxException use) {
            throw new CommandProcessException(use.getMessage());
        }
        return uri;
    }

    private class LoadScriptListenerImpl implements LoadScriptListener {

        @Override
        public void loadedScript(Script script) {
            JSONObject jsonParams = new JSONObject();
            jsonParams.put("scriptId", Integer.toString(script.getId()));
            jsonParams.put("url", script.getUrl());
            jsonParams.put("startLine", 0);
            jsonParams.put("startColumn", 0);
            Source source = script.getSource();
            int lastLine = source.getLineCount() - 1;
            int lastColumn;
            if (lastLine < 0) {
                lastLine = 0;
                lastColumn = 0;
            } else {
                lastColumn = source.getLineLength(lastLine + 1);
                int srcMapLine = lastLine + 1;
                CharSequence line;
                do {
                    line = source.getCharacters(srcMapLine);
                    srcMapLine--;
                    // Node.js wraps source into a function, skip empty lines and end of a function.
                } while (srcMapLine > 0 && (line.length() == 0 || "});".equals(line)));
                CharSequence sourceMapURL = (srcMapLine > 0) ? getSourceMapURL(source, srcMapLine) : null;
                if (sourceMapURL != null) {
                    jsonParams.put("sourceMapURL", sourceMapURL);
                    lastLine = srcMapLine - 1;
                    lastColumn = source.getLineLength(lastLine + 1);
                }
            }
            jsonParams.put("endLine", lastLine);
            jsonParams.put("endColumn", lastColumn);
            jsonParams.put("executionContextId", context.getId());
            jsonParams.put("hash", script.getHash());
            jsonParams.put("length", source.getLength());
            Params params = new Params(jsonParams);
            Event scriptParsed = new Event("Debugger.scriptParsed", params);
            eventHandler.event(scriptParsed);
        }

        private CharSequence getSourceMapURL(Source source, int lastLine) {
            String mapKeyword = "sourceMappingURL=";
            int mapKeywordLenght = mapKeyword.length();
            CharSequence line = source.getCharacters(lastLine + 1);
            int lineLength = line.length();
            int i = 0;
            while (i < lineLength && Character.isWhitespace(line.charAt(i))) {
                i++;
            }
            if (i + 3 < lineLength && line.charAt(i) == '/' && line.charAt(i + 1) == '/' &&
                            (line.charAt(i + 2) == '#' || line.charAt(i + 2) == '@')) {
                i += 3;
            } else {
                return null;
            }
            while (i < lineLength && Character.isWhitespace(line.charAt(i))) {
                i++;
            }
            if (i + mapKeywordLenght < lineLength && line.subSequence(i, i + mapKeywordLenght).equals(mapKeyword)) {
                i += mapKeywordLenght;
            } else {
                return null;
            }
            return line.subSequence(i, line.length());
        }

    }

    private class SuspendedCallbackImpl implements SuspendedCallback {

        @Override
        public void onSuspend(SuspendedEvent se) {
            SourceSection ss = se.getSourceSection();
            onSuspendPhaser.register();
            try {
                synchronized (suspendLock) {
                    running = false;
                }
                slh.assureLoaded(ss.getSource());
                context.setLastLanguage(ss.getSource().getLanguage(), ss.getSource().getMimeType());
                JSONObject jsonParams = new JSONObject();
                CallFrame[] callFrames = createCallFrames(se.getStackFrames());
                suspendedInfo = new DebuggerSuspendedInfo(se, callFrames);
                context.setSuspendedInfo(suspendedInfo);
                jsonParams.put("callFrames", getFramesParam(callFrames));
                jsonParams.put("reason", "other");  // TODO
                List<Breakpoint> breakpoints = se.getBreakpoints();
                JSONArray bpArr = new JSONArray();
                for (Breakpoint bp : breakpoints) {
                    String id = bph.getId(bp);
                    if (id != null) {
                        bpArr.put(id);
                    }
                }
                jsonParams.put("hitBreakpoints", bpArr);

                Params params = new Params(jsonParams);
                Event paused = new Event("Debugger.paused", params);
                eventHandler.event(paused);
                List<CancellableRunnable> executables;
                for (;;) {
                    executables = null;
                    synchronized (suspendLock) {
                        if (!running && suspendThreadExecutables.isEmpty()) {
                            try {
                                suspendLock.wait();
                            } catch (InterruptedException ex) {
                            }
                        }
                        if (!suspendThreadExecutables.isEmpty()) {
                            executables = new LinkedList<>();
                            CancellableRunnable r;
                            while ((r = suspendThreadExecutables.poll()) != null) {
                                executables.add(r);
                            }
                        }
                        if (running) {
                            suspendedInfo = null;
                            context.setSuspendedInfo(null);
                            break;
                        }
                    }
                    if (executables != null) {
                        for (CancellableRunnable r : executables) {
                            r.run();
                        }
                        executables = null;
                    }
                }
                if (executables != null) {
                    for (CancellableRunnable r : executables) {
                        r.cancel();
                    }
                }
                Event resumed = new Event("Debugger.resumed", null);
                eventHandler.event(resumed);
            } finally {
                onSuspendPhaser.arrive();
            }
        }

        private JSONArray getFramesParam(CallFrame[] callFrames) {
            JSONArray array = new JSONArray();
            for (CallFrame cf : callFrames) {
                array.put(cf.toJSON());
            }
            return array;
        }

    }

}
