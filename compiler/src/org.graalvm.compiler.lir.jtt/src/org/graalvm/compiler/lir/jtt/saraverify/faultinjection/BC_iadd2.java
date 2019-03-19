package org.graalvm.compiler.lir.jtt.saraverify.faultinjection;

import org.graalvm.compiler.debug.GraalError;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class BC_iadd2 extends InjectorTest {

    @Override
    public Injector getInjectorPhase() {
        Injector injector = new Injector();
        return injector.new DuplicateInstructionInjector();
    }

    private final String UNIQUE_INSTRUCTION_ERROR_MSG = "LIR instructions are not unique.";

    @Rule public ExpectedException thrown = ExpectedException.none();

    public static int test(byte a, byte b) {
        return a + b;
    }

    @Test
    public void run0() throws Throwable {
        thrown.expect(GraalError.class);
        thrown.expectMessage(UNIQUE_INSTRUCTION_ERROR_MSG);
        runTest("test", ((byte) 1), ((byte) 2));
    }

    @Test
    public void run1() throws Throwable {
        thrown.expect(GraalError.class);
        thrown.expectMessage(UNIQUE_INSTRUCTION_ERROR_MSG);
        runTest("test", ((byte) 0), ((byte) -1));
    }

    @Test
    public void run2() throws Throwable {
        thrown.expect(GraalError.class);
        thrown.expectMessage(UNIQUE_INSTRUCTION_ERROR_MSG);
        runTest("test", ((byte) 33), ((byte) 67));
    }

    @Test
    public void run3() throws Throwable {
        thrown.expect(GraalError.class);
        thrown.expectMessage(UNIQUE_INSTRUCTION_ERROR_MSG);
        runTest("test", ((byte) 1), ((byte) -1));
    }

    @Test
    public void run4() throws Throwable {
        thrown.expect(GraalError.class);
        thrown.expectMessage(UNIQUE_INSTRUCTION_ERROR_MSG);
        runTest("test", ((byte) -128), ((byte) 1));
    }

    @Test
    public void run5() throws Throwable {
        thrown.expect(GraalError.class);
        thrown.expectMessage(UNIQUE_INSTRUCTION_ERROR_MSG);
        runTest("test", ((byte) 127), ((byte) 1));
    }

}
