package org.graalvm.compiler.lir.saraverify;

import org.graalvm.compiler.lir.gen.LIRGenerationResult;
import org.graalvm.compiler.lir.phases.AllocationPhase.AllocationContext;
import org.graalvm.compiler.lir.phases.LIRPhase;
import org.graalvm.compiler.options.Option;
import org.graalvm.compiler.options.OptionKey;
import org.graalvm.compiler.options.OptionType;

import jdk.vm.ci.code.TargetDescription;

public class InjectorVerificationPhase extends LIRPhase<AllocationContext> {

    public static class Options {
        // @formatter:off
        @Option(help = "Enable fault injector for the static analysis register allocation verification.", type = OptionType.Debug)
        public static final OptionKey<Boolean> SARAVerifyInjector = new OptionKey<>(false);
        // @formatter:on
    }

    @Override
    protected void run(TargetDescription target, LIRGenerationResult lirGenRes, AllocationContext context) {
        VerificationPhase.runVerification(lirGenRes, context);
    }

}
