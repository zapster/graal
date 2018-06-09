package org.graalvm.compiler.lir.jtt.saraverify.faultinjection;

import java.util.ListIterator;

import org.graalvm.compiler.api.directives.GraalDirectives;
import org.graalvm.compiler.jtt.JTTTest;
import org.graalvm.compiler.lir.alloc.RegisterAllocationPhase;
import org.graalvm.compiler.lir.phases.AllocationPhase.AllocationContext;
import org.graalvm.compiler.lir.phases.LIRPhase;
import org.graalvm.compiler.lir.phases.LIRSuites;
import org.graalvm.compiler.lir.saraverify.SARAVerifyError;
import org.graalvm.compiler.options.OptionValues;
import org.junit.Assert;
import org.junit.Test;

public class DemoTest extends JTTTest {

    public static final String BOLD_RED = "\u001b[31;1m";
    public static final String RESET = "\u001b[0m";
    public static final DemoInjector demoInjector = new DemoInjector();

    public static int test(int n, int x) {
        GraalDirectives.spillRegisters();
        int m;
        if (n > 0) {
            GraalDirectives.controlFlowAnchor();
            m = x;
        } else {
            m = 42 + x;
        }
        GraalDirectives.controlFlowAnchor();
        return n - m;
    }

    @Test
    public void run0() throws Throwable {
        try {
            runTest("test", 1, 5);
        } catch (SARAVerifyError error) {
            System.err.println(BOLD_RED);
            System.err.println(error.getMessage());
            System.err.println(RESET);
            Assert.assertTrue(false);
        }
    }

// @Override
// protected LIRSuites createLIRSuites(OptionValues opts) {
// LIRSuites lirSuites = super.createLIRSuites(opts);
//
// ListIterator<LIRPhase<AllocationContext>> phase =
// lirSuites.getAllocationStage().findPhase(RegisterAllocationPhase.class);
// assert phase != null;
// phase.add(demoInjector.getDemoWrongOperandInjector());
//
// return lirSuites;
// }
//
// @Override
// protected LIRSuites createLIRSuites(OptionValues opts) {
// LIRSuites lirSuites = super.createLIRSuites(opts);
//
// ListIterator<LIRPhase<AllocationContext>> phase =
// lirSuites.getAllocationStage().findPhase(RegisterAllocationPhase.class);
// assert phase != null;
// phase.add(demoInjector.getDemoStaleInjector());
//
// return lirSuites;
// }
//
    @Override
    protected LIRSuites createLIRSuites(OptionValues opts) {
        LIRSuites lirSuites = super.createLIRSuites(opts);

        ListIterator<LIRPhase<AllocationContext>> phase = lirSuites.getAllocationStage().findPhase(RegisterAllocationPhase.class);
        assert phase != null;
        phase.add(demoInjector.geDemoEvictedInjector());

        return lirSuites;
    }
}
