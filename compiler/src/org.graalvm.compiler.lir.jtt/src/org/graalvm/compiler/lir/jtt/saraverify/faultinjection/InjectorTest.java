package org.graalvm.compiler.lir.jtt.saraverify.faultinjection;

import java.util.ListIterator;

import org.graalvm.compiler.jtt.JTTTest;
import org.graalvm.compiler.lir.alloc.RegisterAllocationPhase;
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

        VerificationPhase verification = new VerificationPhase();
        OptionValues options = this.getDebugContext().getOptions();

        if (!RegisterAllocationVerificationPhase.Options.SARAVerify.getValue(options)) {
            RegisterAllocationVerificationPhase registerAllocationVerification = new RegisterAllocationVerificationPhase();
            ListIterator<LIRPhase<AllocationContext>> phase = lirSuites.getAllocationStage().findPhase(MarkBasePointersPhase.class);
            assert phase != null;
            phase.add(registerAllocationVerification);

            phase = lirSuites.getAllocationStage().findPhase(RegisterAllocationPhase.class);
            assert phase != null;
            phase.add(verification);
        }

        ListIterator<LIRPhase<AllocationContext>> phase = lirSuites.getAllocationStage().findPhase(RegisterAllocationPhase.class);
        assert phase != null;
        phase.add(getInjectorPhase());

        return lirSuites;
    }

    public abstract Injector getInjectorPhase();

}
