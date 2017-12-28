package org.graalvm.compiler.lir.jtt.saraverify;

import java.util.ListIterator;

import org.graalvm.compiler.jtt.JTTTest;
import org.graalvm.compiler.lir.alloc.lsra.LinearScanPhase;
import org.graalvm.compiler.lir.dfa.MarkBasePointersPhase;
import org.graalvm.compiler.lir.phases.AllocationPhase.AllocationContext;
import org.graalvm.compiler.lir.phases.LIRPhase;
import org.graalvm.compiler.lir.phases.LIRSuites;
import org.graalvm.compiler.lir.saraverify.RegisterAllocationVerificationPhase;
import org.graalvm.compiler.lir.saraverify.VerificationPhase;
import org.graalvm.compiler.options.OptionValues;

public abstract class InjectorTest extends JTTTest {

    @Override
    protected LIRSuites createLIRSuites(OptionValues opts) {
        LIRSuites lirSuites = super.createLIRSuites(opts);
        RegisterAllocationVerificationPhase registerAllocationVerification = new RegisterAllocationVerificationPhase();
        VerificationPhase verification = new VerificationPhase();

        ListIterator<LIRPhase<AllocationContext>> phase = lirSuites.getAllocationStage().findPhase(MarkBasePointersPhase.class);
        assert phase != null;
        phase.add(registerAllocationVerification);

        phase = lirSuites.getAllocationStage().findPhase(LinearScanPhase.class);
        assert phase != null;
        phase.add(verification);

        phase = lirSuites.getAllocationStage().findPhase(LinearScanPhase.class);
        assert phase != null;
        phase.add(getInjectorPhase());

        return lirSuites;
    }

    public abstract Injector getInjectorPhase();

}
