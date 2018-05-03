package org.graalvm.compiler.lir.jtt.saraverify.faultinjection;

import java.util.ListIterator;

import org.graalvm.compiler.api.directives.GraalDirectives;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.jtt.JTTTest;
import org.graalvm.compiler.lir.alloc.RegisterAllocationPhase;
import org.graalvm.compiler.lir.phases.AllocationPhase.AllocationContext;
import org.graalvm.compiler.lir.phases.LIRPhase;
import org.graalvm.compiler.lir.phases.LIRSuites;
import org.graalvm.compiler.options.OptionValues;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class DemoTest extends JTTTest {

    @Rule public ExpectedException thrown = ExpectedException.none();

    public static int test(int n) {
        int m;
        if (n > 0) {
            GraalDirectives.controlFlowAnchor();
            m = 5;
        } else {
            m = 42;
        }
        GraalDirectives.controlFlowAnchor();
        return n - m;
    }

    @Test
    public void run0() throws Throwable {
        thrown.expect(GraalError.class);
        thrown.expectMessage("Used register rax is not defined.");
        runTest("test", 1);
    }

    @Override
    protected LIRSuites createLIRSuites(OptionValues opts) {
        LIRSuites lirSuites = super.createLIRSuites(opts);

        ListIterator<LIRPhase<AllocationContext>> phase = lirSuites.getAllocationStage().findPhase(RegisterAllocationPhase.class);
        assert phase != null;
        phase.add(new DemoInjector());

        return lirSuites;
    }
}
