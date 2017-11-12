package org.graalvm.compiler.lir.jtt.saraverify;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.StandardOp.JumpOp;
import org.graalvm.compiler.lir.StandardOp.LabelOp;
import org.graalvm.compiler.lir.jtt.saraverify.TestOp.TestBinary;
import org.graalvm.compiler.lir.jtt.saraverify.TestOp.TestReturn;
import org.graalvm.compiler.lir.saraverify.DuPair;
import org.graalvm.compiler.lir.saraverify.DuSequenceAnalysis;
import org.junit.Test;

import jdk.vm.ci.code.Register;
import jdk.vm.ci.meta.Value;

public class DuSequenceAnalysisTest {

    private Register rbp = new Register(11, 0, "rbp", Register.SPECIAL);
    private Register r0 = new Register(0, 0, "r0", Register.SPECIAL);
    private Register r1 = new Register(1, 0, "r1", Register.SPECIAL);
    private Register r2 = new Register(2, 0, "r2", Register.SPECIAL);

    @Test
    public void testDetermineDuPairs() {
        ArrayList<LIRInstruction> instructions = new ArrayList<>();

        LabelOp labelOp = new LabelOp(null, true);
        labelOp.addIncomingValues(new Value[]{r0.asValue(), r1.asValue(), rbp.asValue()});
        TestBinary addOp = new TestBinary(TestBinary.ArithmeticOpcode.ADD, r2.asValue(), r0.asValue(), r1.asValue());
        TestBinary addOp2 = new TestBinary(TestBinary.ArithmeticOpcode.ADD, r2.asValue(), r1.asValue(), r2.asValue());
        TestReturn returnOp = new TestReturn(rbp.asValue(), r2.asValue());

        instructions.add(labelOp);
        instructions.add(addOp);
        instructions.add(addOp2);
        instructions.add(new JumpOp(null));
        instructions.add(returnOp);

        List<DuPair> expected = new ArrayList<>();
        expected.add(new DuPair(rbp.asValue(), instructions.get(0), instructions.get(4), 2, 0));
        expected.add(new DuPair(r0.asValue(), instructions.get(0), instructions.get(1), 0, 0));
        expected.add(new DuPair(r1.asValue(), instructions.get(0), instructions.get(1), 1, 1));
        expected.add(new DuPair(r2.asValue(), instructions.get(1), instructions.get(2), 0, 1));
        expected.add(new DuPair(r2.asValue(), instructions.get(2), instructions.get(4), 0, 1));
        expected.add(new DuPair(r1.asValue(), instructions.get(0), instructions.get(2), 1, 0));

        List<DuPair> actual = DuSequenceAnalysis.determineDuPairs(instructions);

        assertEqualsDuPairs(expected, actual);
    }

    @Test
    public void testDuPairEquals() {
        LabelOp labelOp = new LabelOp(null, true);
        LabelOp labelOp2 = new LabelOp(null, true);
        DuPair duPair = new DuPair(r0.asValue(), labelOp, labelOp2, 0, 0);
        assertEquals(duPair, duPair);

        DuPair duPair2 = new DuPair(r1.asValue(), labelOp, labelOp2, 0, 0);
        assertNotEquals(duPair, duPair2);

        DuPair duPair3 = new DuPair(r0.asValue(), labelOp, null, 0, 0);
        assertNotEquals(duPair, duPair3);

        DuPair duPair4 = new DuPair(r0.asValue(), null, labelOp2, 0, 0);
        assertNotEquals(duPair, duPair4);

        DuPair duPair5 = new DuPair(r0.asValue(), labelOp, labelOp2, 1, 0);
        assertNotEquals(duPair, duPair5);

        DuPair duPair6 = new DuPair(r0.asValue(), labelOp, labelOp2, 0, 1);
        assertNotEquals(duPair, duPair6);
    }

    private static void assertEqualsDuPairs(List<DuPair> expected, List<DuPair> actual) {
        assertEquals("The number of DuPairs do not match.", expected.size(), actual.size());

        for (DuPair duPair : expected) {
            assert actual.remove(duPair);
        }
    }
}
