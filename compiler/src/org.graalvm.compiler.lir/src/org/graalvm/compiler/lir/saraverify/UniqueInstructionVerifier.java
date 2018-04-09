package org.graalvm.compiler.lir.saraverify;

import java.util.IdentityHashMap;
import java.util.Map;

import org.graalvm.compiler.core.common.cfg.AbstractBlockBase;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.lir.LIR;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.gen.LIRGenerationResult;

public class UniqueInstructionVerifier {

    public static boolean verify(LIRGenerationResult lirGenRes) {
        LIR lir = lirGenRes.getLIR();
        AbstractBlockBase<?>[] blocks = lir.getControlFlowGraph().getBlocks();

        Map<LIRInstruction, ?> instructions = new IdentityHashMap<>();

        for (AbstractBlockBase<?> block : blocks) {
            for (LIRInstruction instr : lir.getLIRforBlock(block)) {
                if (instructions.containsKey(instr)) {
                    GraalError.shouldNotReachHere("LIR instructions are not unique.");
                }
                instructions.put(instr, null);
            }
        }
        return true;
    }
}
