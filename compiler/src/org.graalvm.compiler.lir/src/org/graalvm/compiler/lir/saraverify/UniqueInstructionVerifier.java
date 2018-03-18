package org.graalvm.compiler.lir.saraverify;

import java.util.Arrays;

import org.graalvm.compiler.core.common.cfg.AbstractBlockBase;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.lir.LIR;
import org.graalvm.compiler.lir.gen.LIRGenerationResult;

public class UniqueInstructionVerifier {

    public static void verify(LIRGenerationResult lirGenRes) {
        LIR lir = lirGenRes.getLIR();
        AbstractBlockBase<?>[] blocks = lir.getControlFlowGraph().getBlocks();

        long instructionsCount = Arrays.stream(blocks)  //
                        .flatMap(block -> lir.getLIRforBlock(block).stream())    //
                        .count();
        long distinctCount = Arrays.stream(blocks)      //
                        .flatMap(block -> lir.getLIRforBlock(block).stream())    //
                        .map(instr -> System.identityHashCode(instr))   //
                        .distinct() //
                        .count();

        if (instructionsCount != distinctCount) {
            GraalError.shouldNotReachHere("LIR instructions are not unique.");
        }
    }
}
