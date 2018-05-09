package org.graalvm.compiler.lir.saraverify;

import org.graalvm.compiler.lir.gen.LIRGenerationResult;
import org.graalvm.compiler.lir.phases.AllocationPhase.AllocationContext;
import org.graalvm.compiler.lir.phases.LIRPhase;

import jdk.vm.ci.code.TargetDescription;

public class VerificationPhase extends LIRPhase<AllocationContext> {

    @Override
    protected void run(TargetDescription target, LIRGenerationResult lirGenRes, AllocationContext context) {
        // TODO Auto-generated method stub

    }
}
