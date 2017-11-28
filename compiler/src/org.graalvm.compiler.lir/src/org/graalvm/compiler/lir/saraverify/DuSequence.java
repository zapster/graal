package org.graalvm.compiler.lir.saraverify;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.ListIterator;

import jdk.vm.ci.meta.AllocatableValue;

public class DuSequence {

	private LinkedList<DuPair> duPairs;

	public DuSequence(DuPair duPair) {
		duPairs = new LinkedList<>();
		duPairs.add(duPair);
	}

	public void addFirst(DuPair duPair) {
		duPairs.addFirst(duPair);
	}

	public DuPair peekFirst() {
		return duPairs.peekFirst();
	}

	public DuPair peekLast() {
		return duPairs.peekLast();
	}

	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof DuSequence)) {
			return false;
		}

		DuSequence duSequence = (DuSequence) obj;
		if (duSequence.duPairs.size() != this.duPairs.size()) {
			return false;
		}

		ListIterator<DuPair> duPairsIterator = this.duPairs.listIterator();

		while (duPairsIterator.hasNext()) {
			DuPair duPair1 = duSequence.duPairs.get(duPairsIterator.nextIndex());
			DuPair duPair2 = duPairsIterator.next();

			if (!(duPair1.equals(duPair2))) {
				return false;
			}
		}

		return true;
	}

	@Override
	public String toString() {
		String values = "\nValues:";
		String instructions = "Def at pos ";

		Iterator<DuPair> duPairsIterator = duPairs.iterator();

		DuPair duPair = duPairsIterator.next();
		AllocatableValue lastValue = duPair.getValue();
		values = values + " " + lastValue;
		instructions = instructions + duPair.getOperandDefPosition() + " in: " + duPair.getDefInstruction();

		while (duPairsIterator.hasNext()) {
			duPair = duPairsIterator.next();

			lastValue = duPair.getValue();
			values = values + " -> " + lastValue;

			instructions = instructions + "\nCopy: " + duPair.getDefInstruction();
		}

		instructions = instructions + "\nUse at pos " + duPair.getOperandUsePosition() + " in: "
				+ duPair.getUseInstruction();

		return values + "\n" + instructions;
	}

	@Override
	public int hashCode() {
		return super.hashCode();
	}
}
