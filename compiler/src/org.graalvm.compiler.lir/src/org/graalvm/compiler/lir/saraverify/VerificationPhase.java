package org.graalvm.compiler.lir.saraverify;

import java.util.ArrayList;
import java.util.Optional;

import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.gen.LIRGenerationResult;
import org.graalvm.compiler.lir.phases.AllocationPhase.AllocationContext;
import org.graalvm.compiler.lir.phases.LIRPhase;

import jdk.vm.ci.code.TargetDescription;

public class VerificationPhase extends LIRPhase<AllocationContext> {

	@Override
	protected void run(TargetDescription target, LIRGenerationResult lirGenRes, AllocationContext context) {
		AnalysisResult result = context.contextLookup(AnalysisResult.class);
	}

	public boolean verifyDataFlow(ArrayList<DuSequence> inputDuSequences, ArrayList<DuSequence> outputDuSequences) {

		if (inputDuSequences.size() != outputDuSequences.size()) {
			return false;
		}

		assert inputDuSequences.stream().distinct().count() == outputDuSequences.stream().distinct().count();

		for (DuSequence inputDuSequence : inputDuSequences) {
			LIRInstruction inputDefInstruction = inputDuSequence.peekFirst().getDefInstruction();
			LIRInstruction inputUseInstruction = inputDuSequence.peekLast().getUseInstruction();
			int inputOperandDefPosition = inputDuSequence.peekFirst().getOperandDefPosition();
			int inputOperandUsePosition = inputDuSequence.peekLast().getOperandUsePosition();

			boolean match = outputDuSequences.stream()
					.anyMatch(duSequence -> duSequence.peekFirst().getOperandDefPosition() == inputOperandDefPosition &&
							duSequence.peekLast().getOperandUsePosition() == inputOperandUsePosition &&
							duSequence.peekFirst().getDefInstruction().equals(inputDefInstruction) &&
							duSequence.peekLast().getUseInstruction().equals(inputUseInstruction));

			if (!match) {
				System.out.println("Input Sequence with wrong or missing output sequence: " + inputDuSequence);
				return false;
			}
		}

		return true;
	}
}
