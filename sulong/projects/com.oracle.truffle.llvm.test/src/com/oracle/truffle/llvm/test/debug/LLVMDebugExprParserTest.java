package com.oracle.truffle.llvm.test.debug;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.graalvm.polyglot.Context.Builder;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.oracle.truffle.api.debug.Breakpoint;
import com.oracle.truffle.api.debug.DebugException;
import com.oracle.truffle.api.debug.DebugStackFrame;
import com.oracle.truffle.api.debug.DebuggerSession;
import com.oracle.truffle.api.debug.SuspendedCallback;
import com.oracle.truffle.api.debug.SuspendedEvent;
import com.oracle.truffle.llvm.runtime.LLVMLanguage;
import com.oracle.truffle.llvm.test.options.TestOptions;
import com.oracle.truffle.tck.DebuggerTester;

@RunWith(Parameterized.class)
public final class LLVMDebugExprParserTest {

    private static final Path BC_DIR_PATH = Paths.get(TestOptions.TEST_SUITE_PATH, "debugexpr");
    private static final Path SRC_DIR_PATH = Paths.get(TestOptions.PROJECT_ROOT, "..", "tests", "com.oracle.truffle.llvm.tests.debugexpr", "debugexpr");
    private static final Path TRACE_DIR_PATH = Paths.get(TestOptions.PROJECT_ROOT, "..", "tests", "com.oracle.truffle.llvm.tests.debugexpr", "testExpr");

    private static final String OPTION_ENABLE_LVI = "llvm.enableLVI";

    private static final String CONFIGURATION = "O1.bc";

    public LLVMDebugExprParserTest(String testName, String configuration) {
        this.testName = testName;
        this.configuration = configuration;
    }

    static void setContextOptions(Builder contextBuilder) {
// contextBuilder.option(EXPERIMENTAL_OPTIONS, String.valueOf(true));
        contextBuilder.option(OPTION_ENABLE_LVI, String.valueOf(true));
    }

    @Parameters(name = "{0}")
    public static Collection<Object[]> getConfigurations() {
        try (Stream<Path> dirs = Files.walk(BC_DIR_PATH)) {
            return dirs.filter(path -> path.endsWith(CONFIGURATION)).map(path -> new Object[]{path.getParent().getFileName().toString(), CONFIGURATION}).collect(Collectors.toSet());
        } catch (IOException e) {
            throw new AssertionError("Error while finding tests!", e);
        }
    }

    private static final String LANG_ID = LLVMLanguage.ID;

    private static final String SOURCE_FILE_EXTENSIONS = ".c";
    private static final String TRACE_EXT = ".txt";
    private static final String OPTION_LAZY_PARSING = "llvm.lazyParsing";

    private final String testName;
    private final String configuration;

    private DebuggerTester tester;

    String getTestName() {
        return testName;
    }

    @Before
    public void before() {
        final Context.Builder contextBuilder = Context.newBuilder(LANG_ID);
        contextBuilder.allowAllAccess(true);
        contextBuilder.option(OPTION_LAZY_PARSING, String.valueOf(false));
        setContextOptions(contextBuilder);
        tester = new DebuggerTester(contextBuilder);
    }

    @After
    public void dispose() {
        tester.close();
    }

    private static Source loadSource(File file) {
        Source source;
        try {
            final File canonicalFile = file.getCanonicalFile();
            source = Source.newBuilder(LANG_ID, canonicalFile).build();
        } catch (IOException ex) {
            throw new AssertionError("Could not load source: " + file.getPath(), ex);
        }
        return source;
    }

    private Source loadOriginalSource() {
        final File file = SRC_DIR_PATH.resolve(testName + SOURCE_FILE_EXTENSIONS).toFile();
        if (file.exists()) {
            return loadSource(file);
        }
        throw new AssertionError("Could not locate source for test: " + testName);
    }

    private Source loadBitcodeSource() {
        final Path path = BC_DIR_PATH.resolve(Paths.get(testName, configuration));
        return loadSource(path.toFile());
    }

    private TestExpressions fetchExpressions() {
        final Path path = TRACE_DIR_PATH.resolve(testName + TRACE_EXT);
        return TestExpressions.parse(path);
    }

    private static final class BreakInfo {

        private int lastStop;
        private ContinueStrategy lastStrategy;

        BreakInfo() {
            this.lastStop = -1;
            this.lastStrategy = null;
        }

        int getLastStop() {
            return lastStop;
        }

        void setLastStop(int lastStop) {
            this.lastStop = lastStop;
        }

        ContinueStrategy getLastStrategy() {
            return lastStrategy;
        }

        void setLastStrategy(ContinueStrategy lastStrategy) {
            this.lastStrategy = lastStrategy;
        }
    }

    private static final class TestCallback implements SuspendedCallback {

        private final BreakInfo info;
        private final StopRequest bpr;
        private final Map<String, String> textExpressionMap;

        TestCallback(BreakInfo info, StopRequest bpr, Map<String, String> textExpressionMap) {
            this.info = info;
            this.bpr = bpr;
            this.textExpressionMap = textExpressionMap;
        }

        private static void setStrategy(SuspendedEvent event, DebugStackFrame frame, ContinueStrategy strategy) {
            if (strategy != null) {
                strategy.prepareInEvent(event, frame);
            } else {
                ContinueStrategy.STEP_INTO.prepareInEvent(event, frame);
            }
        }

        @Override
        public void onSuspend(SuspendedEvent event) {
            final DebugStackFrame frame = event.getTopStackFrame();

            final int currentLine = event.getSourceSection().getStartLine();

            if (currentLine == info.getLastStop()) {
                // since we are stepping on IR-instructions rather than source-statements it can
                // happen that we step at the same line multiple times, so we simply try the last
                // action again. The exact stops differ between LLVM versions and optimization
                // levels which would make it difficult to record an exact trace.
                setStrategy(event, frame, info.getLastStrategy());
                return;

            } else if (currentLine == bpr.getLine()) {
                info.setLastStop(currentLine);
                info.setLastStrategy(bpr.getNextAction());
                setStrategy(event, frame, bpr.getNextAction());

            } else {
                throw new AssertionError(String.format("Unexpected stop at line %d", currentLine));
            }

            for (Entry<String, String> kv : textExpressionMap.entrySet()) {
                if (kv.getValue().startsWith("EXCEPTION ")) {
                    try {
                        String actual = frame.eval(kv.getKey()).as(String.class);
                        assertTrue("Evaluation of expression \"" + kv.getKey() + "\" did evaluate to " + actual + " and did not throw expected " + kv.getValue(), false);
                    } catch (DebugException e) {
                        // OK since expected exception has been thrown
                    }
                } else {
                    String actual = frame.eval(kv.getKey()).as(String.class);
                    assertEquals("Evaluation of expression \"" + kv.getKey() + "\" produced unexpected result: ", kv.getValue(), actual);
                }
            }
        }

        boolean isDone() {
            return info.getLastStop() == bpr.getLine();
        }
    }

    private static Breakpoint buildBreakPoint(Source source, int line) {
        return Breakpoint.newBuilder(source.getURI()).lineIs(line).build();
    }

    private void runTest(Source source, Source bitcode, TestExpressions testExpr) {
        try (DebuggerSession session = tester.startSession()) {
            testExpr.requestedBreakpoints().forEach(line -> session.install(buildBreakPoint(source, line)));

            tester.startEval(bitcode);

            final BreakInfo info = new BreakInfo();
            for (StopRequest bpr : testExpr) {
                final TestCallback expectedEvent = new TestCallback(info, bpr, testExpr.getExpressions(bpr));
                do {
                    tester.expectSuspended(expectedEvent);
                } while (!expectedEvent.isDone());
            }

            tester.expectDone();
        }
    }

    @Test
    public void test() throws Throwable {
        final TestExpressions testExpr = fetchExpressions();

        final Source source = loadOriginalSource();
        final Source bitcode = loadBitcodeSource();
        runTest(source, bitcode, testExpr);
    }

}
