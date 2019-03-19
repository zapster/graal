/*
 * Copyright (c) 2013, 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.graal;

import static com.oracle.svm.core.util.VMError.shouldNotReachHere;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

import org.graalvm.compiler.code.CompilationResult;
import org.graalvm.compiler.core.CompilationWrapper;
import org.graalvm.compiler.core.CompilationWrapper.ExceptionAction;
import org.graalvm.compiler.core.GraalCompiler;
import org.graalvm.compiler.core.target.Backend;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.Indent;
import org.graalvm.compiler.lir.asm.CompilationResultBuilderFactory;
import org.graalvm.compiler.lir.phases.LIRSuites;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.options.Option;
import org.graalvm.compiler.options.OptionValues;
import org.graalvm.compiler.phases.OptimisticOptimizations;
import org.graalvm.compiler.phases.tiers.Suites;
import org.graalvm.compiler.printer.GraalDebugHandlersFactory;

import com.oracle.svm.core.SubstrateUtil;
import com.oracle.svm.core.amd64.AMD64CPUFeatureAccess;
import com.oracle.svm.core.graal.code.SubstrateCompilationIdentifier;
import com.oracle.svm.core.graal.code.SubstrateCompilationResult;
import com.oracle.svm.core.graal.meta.InstalledCodeBuilder;
import com.oracle.svm.core.graal.meta.RuntimeConfiguration;
import com.oracle.svm.core.log.Log;
import com.oracle.svm.core.meta.SharedMethod;
import com.oracle.svm.core.option.RuntimeOptionKey;
import com.oracle.svm.graal.meta.SubstrateInstalledCodeImpl;
import com.oracle.svm.graal.meta.SubstrateMethod;

import jdk.vm.ci.amd64.AMD64;
import jdk.vm.ci.code.InstalledCode;

public class SubstrateGraalUtils {

    /** Compile and install the method. Return the installed code descriptor. */
    public static InstalledCode compileAndInstall(OptionValues options, SubstrateMethod method) {
        return compileAndInstall(options, GraalSupport.getRuntimeConfig(), GraalSupport.getSuites(), GraalSupport.getLIRSuites(), method);
    }

    public static InstalledCode compileAndInstall(OptionValues options, RuntimeConfiguration runtimeConfig, Suites suites, LIRSuites lirSuites, SubstrateMethod method) {
        return compileAndInstall(options, runtimeConfig, suites, lirSuites, method, false);
    }

    public static InstalledCode compileAndInstall(OptionValues options, SubstrateMethod method, boolean testTrampolineJumps) {
        return compileAndInstall(options, GraalSupport.getRuntimeConfig(), GraalSupport.getSuites(), GraalSupport.getLIRSuites(), method, testTrampolineJumps);
    }

    public static InstalledCode compileAndInstall(OptionValues options, RuntimeConfiguration runtimeConfig, Suites suites, LIRSuites lirSuites, SubstrateMethod method, boolean testTrampolineJumps) {
        updateGraalArchitectureWithHostCPUFeatures(runtimeConfig.lookupBackend(method));

        DebugContext debug = DebugContext.create(options, new GraalDebugHandlersFactory(GraalSupport.getRuntimeConfig().getSnippetReflection()));

        // create the installed code descriptor
        SubstrateInstalledCodeImpl installedCode = new SubstrateInstalledCodeImpl(method);
        // do compilation and code installation and update the installed code descriptor
        SubstrateGraalUtils.doCompileAndInstall(debug, runtimeConfig, suites, lirSuites, method, installedCode, testTrampolineJumps);
        // return the installed code
        return installedCode;
    }

    /**
     * This method does the actual compilation and installation of the method. Nothing is returned
     * by this call. The code is installed via pinned objects and the address is updated in the
     * {@link InstalledCode} argument.
     *
     * For zone allocation this is where the zone boundary can be placed when the code needs to be
     * compiled and installed.
     */
    private static void doCompileAndInstall(DebugContext debug, RuntimeConfiguration runtimeConfig, Suites suites, LIRSuites lirSuites, SubstrateMethod method,
                    SubstrateInstalledCodeImpl installedCode, boolean testTrampolineJumps) {
        CompilationResult compilationResult = doCompile(debug, runtimeConfig, suites, lirSuites, method);
        installMethod(method, compilationResult, installedCode, testTrampolineJumps);
    }

    private static void installMethod(SubstrateMethod method, CompilationResult result, SubstrateInstalledCodeImpl installedCode, boolean testTrampolineJumps) {
        InstalledCodeBuilder installedCodeBuilder = new InstalledCodeBuilder(method, result, installedCode, null, testTrampolineJumps);
        installedCodeBuilder.install();

        Log.log().string("Installed code for " + method.format("%H.%n(%p)") + ": " + result.getTargetCodeSize() + " bytes").newline();
    }

    /** Does the compilation of the method and returns the compilation result. */
    public static CompilationResult compile(DebugContext debug, final SubstrateMethod method) {
        return compile(debug, GraalSupport.getRuntimeConfig(), GraalSupport.getSuites(), GraalSupport.getLIRSuites(), method);
    }

    public static CompilationResult compile(DebugContext debug, RuntimeConfiguration runtimeConfig, Suites suites, LIRSuites lirSuites, final SubstrateMethod method) {
        updateGraalArchitectureWithHostCPUFeatures(runtimeConfig.lookupBackend(method));
        return doCompile(debug, runtimeConfig, suites, lirSuites, method);
    }

    private static final Map<ExceptionAction, Integer> compilationProblemsPerAction = new EnumMap<>(ExceptionAction.class);

    /**
     * Actual method compilation.
     *
     * For zone allocation this is where the zone boundary can be placed when the code is only
     * compiled. However using the returned compilation result would result into a zone allocation
     * invariant violation.
     */
    private static CompilationResult doCompile(DebugContext initialDebug, RuntimeConfiguration runtimeConfig, Suites suites, LIRSuites lirSuites, final SubstrateMethod method) {

        String methodString = method.format("%H.%n(%p)");
        SubstrateCompilationIdentifier compilationId = new SubstrateCompilationIdentifier();

        return new CompilationWrapper<CompilationResult>(GraalSupport.get().getDebugOutputDirectory(), compilationProblemsPerAction) {
            @SuppressWarnings({"unchecked", "unused"})
            <E extends Throwable> RuntimeException silenceThrowable(Class<E> type, Throwable ex) throws E {
                throw (E) ex;
            }

            @Override
            protected CompilationResult handleException(Throwable t) {
                throw silenceThrowable(RuntimeException.class, t);
            }

            @Override
            protected CompilationResult performCompilation(DebugContext debug) {
                StructuredGraph graph = GraalSupport.decodeGraph(debug, null, compilationId, method);
                return compileGraph(runtimeConfig, suites, lirSuites, method, graph);
            }

            @Override
            public String toString() {
                return methodString;
            }

            @Override
            protected DebugContext createRetryDebugContext(OptionValues options) {
                return GraalSupport.get().openDebugContext(options, compilationId, method);
            }
        }.run(initialDebug);
    }

    private static boolean architectureInitialized;

    /**
     * Updates the architecture in Graal at run-time in order to enable best code generation on the
     * given machine.
     *
     * Note: this method is not synchronized as it only introduces new features to the enum map
     * which is backed by an array. If two threads repeat the work nothing can go wrong.
     *
     * @param graalBackend The graal backend that should be updated.
     */
    public static void updateGraalArchitectureWithHostCPUFeatures(Backend graalBackend) {
        if (SubstrateUtil.HOSTED) {
            throw shouldNotReachHere("Architecture should be updated only at runtime.");
        }

        if (!architectureInitialized) {
            architectureInitialized = true;

            AMD64CPUFeatureAccess.verifyHostSupportsArchitecture(graalBackend.getCodeCache().getTarget().arch);

            AMD64 architecture = (AMD64) graalBackend.getCodeCache().getTarget().arch;
            EnumSet<AMD64.CPUFeature> features = AMD64CPUFeatureAccess.determineHostCPUFeatures();
            architecture.getFeatures().addAll(features);
        }
    }

    public static CompilationResult compileGraph(final SharedMethod method, final StructuredGraph graph) {
        return compileGraph(GraalSupport.getRuntimeConfig(), GraalSupport.getSuites(), GraalSupport.getLIRSuites(), method, graph);
    }

    public static class Options {
        @Option(help = "Force-dump graphs before compilation")//
        public static final RuntimeOptionKey<Boolean> ForceDumpGraphsBeforeCompilation = new RuntimeOptionKey<>(false);
    }

    @SuppressWarnings("try")
    private static CompilationResult compileGraph(RuntimeConfiguration runtimeConfig, Suites suites, LIRSuites lirSuites, final SharedMethod method, final StructuredGraph graph) {
        assert runtimeConfig != null : "no runtime";

        if (Options.ForceDumpGraphsBeforeCompilation.getValue()) {
            /*
             * forceDump is often used during debugging, and we want to make sure that it keeps
             * working, i.e., does not lead to image generation problems when adding a call to it.
             * This code ensures that forceDump is seen as reachable for all images that include
             * Graal, because it is conditional on a runtime option.
             */
            graph.getDebug().forceDump(graph, "Force dump before compilation");
        }

        String methodName = method.format("%h.%n");

        try (DebugContext debug = graph.getDebug();
                        Indent indent = debug.logAndIndent("compile graph %s for method %s", graph, methodName)) {
            OptimisticOptimizations optimisticOpts = OptimisticOptimizations.ALL.remove(OptimisticOptimizations.Optimization.UseLoopLimitChecks);

            final Backend backend = runtimeConfig.lookupBackend(method);

            try (Indent indent2 = debug.logAndIndent("do compilation")) {
                SubstrateCompilationResult result = new SubstrateCompilationResult(graph.compilationId(), method.format("%H.%n(%p)"));
                GraalCompiler.compileGraph(graph, method, backend.getProviders(), backend, null, optimisticOpts, null, suites, lirSuites, result,
                                CompilationResultBuilderFactory.Default, false);
                return result;
            }
        }
    }
}
