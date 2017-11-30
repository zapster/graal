package org.graalvm.compiler.lir.saraverify;

import java.util.ArrayList;

public class AnalysisResult {

	private ArrayList<DuSequence> inputDuSequences;

	public AnalysisResult(ArrayList<DuSequence> inputDuSequences) {
		this.inputDuSequences = inputDuSequences;
	}

	public ArrayList<DuSequence> getInputDuSequences() {
		return inputDuSequences;
	}

}
