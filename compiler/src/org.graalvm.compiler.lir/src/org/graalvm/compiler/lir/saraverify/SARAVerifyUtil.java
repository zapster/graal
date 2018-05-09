package org.graalvm.compiler.lir.saraverify;

import org.graalvm.compiler.lir.InstructionValueConsumer;
import org.graalvm.compiler.lir.LIRInstruction;

public class SARAVerifyUtil {

    public static void visitValues(LIRInstruction instruction, InstructionValueConsumer defConsumer,
                    InstructionValueConsumer useConsumer) {

        instruction.visitEachAlive(useConsumer);
        instruction.visitEachState(useConsumer);
        instruction.visitEachOutput(defConsumer);

// // caller saved registers are handled like temp values
// if (instruction.destroysCallerSavedRegisters()) {
// callerSavedRegisters.forEach(register -> {
// RegisterValue registerValue = register.asValue();
// insertUseNode(instruction, registerValue, unfinishedDuSequences);
// insertDefNode(instruction, registerValue, duSequences, unfinishedDuSequences);
// });
// }
//
// instruction.visitEachTemp(useConsumer);
// instruction.visitEachTemp(defConsumer);
        instruction.visitEachInput(useConsumer);
    }
}
