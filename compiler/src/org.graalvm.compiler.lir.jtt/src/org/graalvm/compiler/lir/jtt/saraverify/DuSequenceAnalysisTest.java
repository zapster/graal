package org.graalvm.compiler.lir.jtt.saraverify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.util.ArrayList;
import java.util.List;

import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.StandardOp.JumpOp;
import org.graalvm.compiler.lir.StandardOp.LabelOp;
import org.graalvm.compiler.lir.Variable;
import org.graalvm.compiler.lir.jtt.saraverify.TestOp.TestBinary;
import org.graalvm.compiler.lir.jtt.saraverify.TestOp.TestMoveFromReg;
import org.graalvm.compiler.lir.jtt.saraverify.TestOp.TestMoveToReg;
import org.graalvm.compiler.lir.jtt.saraverify.TestOp.TestReturn;
import org.graalvm.compiler.lir.saraverify.DuPair;
import org.graalvm.compiler.lir.saraverify.DuSequence;
import org.graalvm.compiler.lir.saraverify.DuSequenceAnalysis;
import org.junit.Test;

import jdk.vm.ci.code.Register;
import jdk.vm.ci.meta.Value;
import jdk.vm.ci.meta.ValueKind;

public class DuSequenceAnalysisTest {

    private Register r0 = new Register(0, 0, "r0", Register.SPECIAL);
    private Register r1 = new Register(1, 0, "r1", Register.SPECIAL);
    private Register r2 = new Register(2, 0, "r2", Register.SPECIAL);
    private Register rbp = new Register(11, 0, "rbp", Register.SPECIAL);
    private Register rax = new Register(12, 0, "rax", Register.SPECIAL);
    private Variable v0 = new Variable(ValueKind.Illegal, 0);
    private Variable v1 = new Variable(ValueKind.Illegal, 1);
    private Variable v2 = new Variable(ValueKind.Illegal, 2);
    private Variable v3 = new Variable(ValueKind.Illegal, 3);

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

        DuSequenceAnalysis duSequenceAnalysis = new DuSequenceAnalysis();
        duSequenceAnalysis.determineDuSequences(instructions);
        List<DuPair> actual = duSequenceAnalysis.getDuPairs();

        assertEqualsDuPairs(expected, actual);
    }

    @Test
    public void testDetermineDuPairs2() {
        ArrayList<LIRInstruction> instructions = new ArrayList<>();

        LabelOp labelOp = new LabelOp(null, true);
        labelOp.addIncomingValues(new Value[]{r0.asValue(), r1.asValue(), rbp.asValue()});
        TestReturn returnOp = new TestReturn(rbp.asValue(), r0.asValue());

        instructions.add(labelOp);
        instructions.add(returnOp);

        List<DuPair> expected = new ArrayList<>();
        expected.add(new DuPair(rbp.asValue(), instructions.get(0), instructions.get(1), 2, 0));
        expected.add(new DuPair(r0.asValue(), instructions.get(0), instructions.get(1), 0, 1));

        DuSequenceAnalysis duSequenceAnalysis = new DuSequenceAnalysis();
        duSequenceAnalysis.determineDuSequences(instructions);
        List<DuPair> actual = duSequenceAnalysis.getDuPairs();

        assertEqualsDuPairs(expected, actual);
    }

    @Test
    public void testDetermineDuPairs3() {
        ArrayList<LIRInstruction> instructions = new ArrayList<>();

        LabelOp labelOp = new LabelOp(null, true);
        labelOp.addIncomingValues(new Value[]{r0.asValue(), rbp.asValue()});
        TestReturn returnOp = new TestReturn(rbp.asValue(), r2.asValue());

        instructions.add(labelOp);
        instructions.add(returnOp);

        List<DuPair> expected = new ArrayList<>();
        expected.add(new DuPair(rbp.asValue(), instructions.get(0), instructions.get(1), 1, 0));

        DuSequenceAnalysis duSequenceAnalysis = new DuSequenceAnalysis();
        duSequenceAnalysis.determineDuSequences(instructions);
        List<DuPair> actual = duSequenceAnalysis.getDuPairs();

        assertEqualsDuPairs(expected, actual);
    }

    // test case represents the instructions from BC_iadd3
    @Test
    public void testDetermineDuPairs4() {
        ArrayList<LIRInstruction> instructions = new ArrayList<>();

        LabelOp i0 = new LabelOp(null, true);
        i0.addIncomingValues(new Value[]{r0.asValue(), r1.asValue(), rbp.asValue()});
        TestMoveFromReg i1 = new TestMoveFromReg(v3, rbp.asValue());
        TestMoveFromReg i2 = new TestMoveFromReg(v0, r0.asValue());
        TestMoveFromReg i3 = new TestMoveFromReg(v1, r1.asValue());
        TestBinary i4 = new TestBinary(TestBinary.ArithmeticOpcode.ADD, v2, v0, v1);
        TestMoveToReg i5 = new TestMoveToReg(rax.asValue(), v2);
        TestReturn i6 = new TestReturn(v3, rax.asValue());

        instructions.add(i0);
        instructions.add(i1);
        instructions.add(i2);
        instructions.add(i3);
        instructions.add(i4);
        instructions.add(i5);
        instructions.add(i6);

        DuPair duPair0 = new DuPair(rbp.asValue(), i0, i1, 2, 0);
        DuPair duPair1 = new DuPair(r0.asValue(), i0, i2, 0, 0);
        DuPair duPair2 = new DuPair(r1.asValue(), i0, i3, 1, 0);
        DuPair duPair3 = new DuPair(v0, i2, i4, 0, 0);
        DuPair duPair4 = new DuPair(v1, i3, i4, 0, 1);
        DuPair duPair5 = new DuPair(v2, i4, i5, 0, 0);
        DuPair duPair6 = new DuPair(v3, i1, i6, 0, 0);
        DuPair duPair7 = new DuPair(rax.asValue(), i5, i6, 0, 1);

        List<DuPair> expectedDuPairs = new ArrayList<>();
        expectedDuPairs.add(duPair0);
        expectedDuPairs.add(duPair1);
        expectedDuPairs.add(duPair2);
        expectedDuPairs.add(duPair3);
        expectedDuPairs.add(duPair4);
        expectedDuPairs.add(duPair5);
        expectedDuPairs.add(duPair6);
        expectedDuPairs.add(duPair7);

        DuSequenceAnalysis duSequenceAnalysis = new DuSequenceAnalysis();
        duSequenceAnalysis.determineDuSequences(instructions);
        List<DuPair> actualDuPairs = duSequenceAnalysis.getDuPairs();
        assertEqualsDuPairs(expectedDuPairs, actualDuPairs);
    }

    @Test
    public void testDetermineDuSequences() {
        ArrayList<LIRInstruction> instructions = new ArrayList<>();

        LabelOp labelOp = new LabelOp(null, true);
        labelOp.addIncomingValues(new Value[]{r0.asValue(), rbp.asValue()});
        TestMoveFromReg moveFromRegOp = new TestMoveFromReg(r1.asValue(), r0.asValue());
        TestReturn returnOp = new TestReturn(rbp.asValue(), r1.asValue());

        instructions.add(labelOp);
        instructions.add(moveFromRegOp);
        instructions.add(returnOp);

        List<DuSequence> expected = new ArrayList<>();
        DuSequence duSequence = new DuSequence(new DuPair(r1.asValue(), moveFromRegOp, returnOp, 0, 1));
        duSequence.addFirst(new DuPair(r0.asValue(), labelOp, moveFromRegOp, 0, 0));
        expected.add(duSequence);
        expected.add(new DuSequence(new DuPair(rbp.asValue(), labelOp, returnOp, 1, 0)));

        DuSequenceAnalysis duSequenceAnalysis = new DuSequenceAnalysis();
        List<DuSequence> actual = duSequenceAnalysis.determineDuSequences(instructions);

        assertEqualsDuSequences(expected, actual);
    }

    @Test
    public void testDuPairEquals() {
        LabelOp labelOp = new LabelOp(null, true);
        TestReturn returnOp = new TestReturn(rbp.asValue(), r0.asValue());
        DuPair duPair = new DuPair(r0.asValue(), labelOp, returnOp, 0, 0);
        assertEquals(duPair, duPair);

        DuPair duPair2 = new DuPair(r1.asValue(), labelOp, returnOp, 0, 0);
        assertNotEquals(duPair, duPair2);

        DuPair duPair3 = new DuPair(r0.asValue(), labelOp, null, 0, 0);
        assertNotEquals(duPair, duPair3);

        DuPair duPair4 = new DuPair(r0.asValue(), null, returnOp, 0, 0);
        assertNotEquals(duPair, duPair4);

        DuPair duPair5 = new DuPair(r0.asValue(), labelOp, returnOp, 1, 0);
        assertNotEquals(duPair, duPair5);

        DuPair duPair6 = new DuPair(r0.asValue(), labelOp, returnOp, 0, 1);
        assertNotEquals(duPair, duPair6);
    }

    @Test
    public void testDuSequenceEquals() {
        LabelOp labelOp = new LabelOp(null, true);
        TestReturn returnOp = new TestReturn(rbp.asValue(), r0.asValue());
        DuPair duPair = new DuPair(r0.asValue(), labelOp, returnOp, 0, 0);
        DuPair duPair2 = new DuPair(r1.asValue(), labelOp, returnOp, 1, 0);
        DuPair duPair3 = new DuPair(r2.asValue(), labelOp, returnOp, 2, 0);

        DuSequence duSequence = new DuSequence(duPair);
        assertEquals(duSequence, duSequence);

        DuSequence duSequence2 = new DuSequence(duPair);
        assertEquals(duSequence, duSequence2);

        duSequence.addFirst(duPair2);
        assertNotEquals(duSequence, duSequence2);

        duSequence2.addFirst(duPair2);
        assertEquals(duSequence, duSequence2);

        duSequence.addFirst(duPair3);
        assertEquals(duSequence.peekFirst(), duPair3);
        assertNotEquals(duSequence, duSequence2);

        duSequence2.addFirst(duPair3);
        assertEquals(duSequence, duSequence2);

        DuSequence duSequence3 = new DuSequence(duPair);
        duSequence.addFirst(duPair3);
        duSequence.addFirst(duPair2);
        assertNotEquals(duSequence, duSequence3);
    }

    private static void assertEqualsDuPairs(List<DuPair> expected, List<DuPair> actual) {
        assertEquals("The number of DuPairs do not match.", expected.size(), actual.size());

        for (DuPair duPair : expected) {
            assert actual.remove(duPair);
        }
    }

    private static void assertEqualsDuSequences(List<DuSequence> expected, List<DuSequence> actual) {
        assertEquals("The number of DuSequences do not match.", expected.size(), actual.size());

        for (DuSequence duSequence : expected) {
            assert actual.remove(duSequence);
        }
    }

}
