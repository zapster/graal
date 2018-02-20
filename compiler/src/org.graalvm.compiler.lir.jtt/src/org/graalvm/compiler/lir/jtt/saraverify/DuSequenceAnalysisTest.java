package org.graalvm.compiler.lir.jtt.saraverify;

import static org.graalvm.compiler.lir.jtt.saraverify.TestValue.r0;
import static org.graalvm.compiler.lir.jtt.saraverify.TestValue.r1;
import static org.graalvm.compiler.lir.jtt.saraverify.TestValue.r2;
import static org.graalvm.compiler.lir.jtt.saraverify.TestValue.r3;
import static org.graalvm.compiler.lir.jtt.saraverify.TestValue.rax;
import static org.graalvm.compiler.lir.jtt.saraverify.TestValue.rbp;
import static org.graalvm.compiler.lir.jtt.saraverify.TestValue.v0;
import static org.graalvm.compiler.lir.jtt.saraverify.TestValue.v1;
import static org.graalvm.compiler.lir.jtt.saraverify.TestValue.v2;
import static org.graalvm.compiler.lir.jtt.saraverify.TestValue.v3;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.lir.ConstantValue;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.StandardOp.JumpOp;
import org.graalvm.compiler.lir.StandardOp.LabelOp;
import org.graalvm.compiler.lir.framemap.SimpleVirtualStackSlot;
import org.graalvm.compiler.lir.jtt.saraverify.TestOp.TestBinary;
import org.graalvm.compiler.lir.jtt.saraverify.TestOp.TestMoveFromConst;
import org.graalvm.compiler.lir.jtt.saraverify.TestOp.TestMoveFromReg;
import org.graalvm.compiler.lir.jtt.saraverify.TestOp.TestMoveToReg;
import org.graalvm.compiler.lir.jtt.saraverify.TestOp.TestReturn;
import org.graalvm.compiler.lir.saraverify.AnalysisResult;
import org.graalvm.compiler.lir.saraverify.DefNode;
import org.graalvm.compiler.lir.saraverify.DuPair;
import org.graalvm.compiler.lir.saraverify.DuSequence;
import org.graalvm.compiler.lir.saraverify.DuSequenceAnalysis;
import org.graalvm.compiler.lir.saraverify.DuSequenceAnalysis.DummyConstDef;
import org.graalvm.compiler.lir.saraverify.DuSequenceAnalysis.DummyRegDef;
import org.graalvm.compiler.lir.saraverify.DuSequenceWeb;
import org.graalvm.compiler.lir.saraverify.MoveNode;
import org.graalvm.compiler.lir.saraverify.Node;
import org.graalvm.compiler.lir.saraverify.SARAVerifyValueComparator;
import org.graalvm.compiler.lir.saraverify.UseNode;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.StackSlot;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.Value;
import jdk.vm.ci.meta.ValueKind;

public class DuSequenceAnalysisTest {

    @Rule public ExpectedException thrown = ExpectedException.none();

    @Test
    public void testDetermineDuPairs0() {
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

        List<DuPair> expectedDuPairs = new ArrayList<>();
        DuPair duPair0 = new DuPair(rbp.asValue(), instructions.get(0), instructions.get(4), 2, 0);
        DuPair duPair1 = new DuPair(r0.asValue(), instructions.get(0), instructions.get(1), 0, 0);
        DuPair duPair2 = new DuPair(r1.asValue(), instructions.get(0), instructions.get(1), 1, 1);
        DuPair duPair3 = new DuPair(r2.asValue(), instructions.get(1), instructions.get(2), 0, 1);
        DuPair duPair4 = new DuPair(r2.asValue(), instructions.get(2), instructions.get(4), 0, 1);
        DuPair duPair5 = new DuPair(r1.asValue(), instructions.get(0), instructions.get(2), 1, 0);
        expectedDuPairs.add(duPair0);
        expectedDuPairs.add(duPair1);
        expectedDuPairs.add(duPair2);
        expectedDuPairs.add(duPair3);
        expectedDuPairs.add(duPair4);
        expectedDuPairs.add(duPair5);

        List<DuSequence> expectedDuSequences = new ArrayList<>();
        DuSequence duSequence0 = new DuSequence(duPair0);
        DuSequence duSequence1 = new DuSequence(duPair1);
        DuSequence duSequence2 = new DuSequence(duPair2);
        DuSequence duSequence3 = new DuSequence(duPair3);
        DuSequence duSequence4 = new DuSequence(duPair4);
        DuSequence duSequence5 = new DuSequence(duPair5);
        expectedDuSequences.add(duSequence0);
        expectedDuSequences.add(duSequence1);
        expectedDuSequences.add(duSequence2);
        expectedDuSequences.add(duSequence3);
        expectedDuSequences.add(duSequence4);
        expectedDuSequences.add(duSequence5);

        List<DuSequenceWeb> expectedDuSequenceWebs = new ArrayList<>();
        DuSequenceWeb web0 = new DuSequenceWeb();
        web0.add(duSequence0);
        DuSequenceWeb web1 = new DuSequenceWeb();
        web1.add(duSequence1);
        DuSequenceWeb web2 = new DuSequenceWeb();
        web2.add(duSequence2);
        web2.add(duSequence5);
        DuSequenceWeb web3 = new DuSequenceWeb();
        web3.add(duSequence3);
        DuSequenceWeb web4 = new DuSequenceWeb();
        web4.add(duSequence4);
        expectedDuSequenceWebs.add(web0);
        expectedDuSequenceWebs.add(web1);
        expectedDuSequenceWebs.add(web2);
        expectedDuSequenceWebs.add(web3);
        expectedDuSequenceWebs.add(web4);

        test(instructions, expectedDuPairs, expectedDuSequences, expectedDuSequenceWebs);
    }

    @Test
    public void testDetermineDuPairs1() {
        ArrayList<LIRInstruction> instructions = new ArrayList<>();

        LabelOp labelOp = new LabelOp(null, true);
        labelOp.addIncomingValues(new Value[]{r0.asValue(), r1.asValue(), rbp.asValue()});
        TestReturn returnOp = new TestReturn(rbp.asValue(), r0.asValue());

        instructions.add(labelOp);
        instructions.add(returnOp);

        DefNode defNodeLabel0 = new DefNode(r0.asValue(), labelOp, 0);
        DefNode defNodeLabel2 = new DefNode(rbp.asValue(), labelOp, 2);
        UseNode useNodeLabel0 = new UseNode(rbp.asValue(), returnOp, 0);
        UseNode useNodeLabel1 = new UseNode(r0.asValue(), returnOp, 1);
        defNodeLabel0.addNextNodes(useNodeLabel1);
        defNodeLabel2.addNextNodes(useNodeLabel0);

        Map<Value, List<Node>> expectedDuSequenceWebs = new TreeMap<>(new SARAVerifyValueComparator());
        List<Node> r0Nodes = new ArrayList<>();
        r0Nodes.add(defNodeLabel0);

        List<Node> rbpNodes = new ArrayList<>();
        rbpNodes.add(defNodeLabel2);

        expectedDuSequenceWebs.put(r0.asValue(), r0Nodes);
        expectedDuSequenceWebs.put(rbp.asValue(), rbpNodes);

        test(instructions, expectedDuSequenceWebs);
    }

    @Test
    public void testDetermineDuPairs2() {
        ArrayList<LIRInstruction> instructions = new ArrayList<>();

        LabelOp labelOp = new LabelOp(null, true);
        labelOp.addIncomingValues(new Value[]{r0.asValue(), rbp.asValue()});
        TestReturn returnOp = new TestReturn(rbp.asValue(), r2.asValue());

        instructions.add(labelOp);
        instructions.add(returnOp);

        List<DuPair> expectedDuPairs = new ArrayList<>();
        DuPair duPair = new DuPair(rbp.asValue(), instructions.get(0), instructions.get(1), 1, 0);
        expectedDuPairs.add(duPair);

        List<DuSequence> expectedDuSequences = new ArrayList<>();
        DuSequence duSequence = new DuSequence(duPair);
        expectedDuSequences.add(duSequence);

        List<DuSequenceWeb> expectedDuSequenceWebs = new ArrayList<>();
        DuSequenceWeb duSequenceWeb = new DuSequenceWeb();
        duSequenceWeb.add(duSequence);
        expectedDuSequenceWebs.add(duSequenceWeb);

        thrown.expect(GraalError.class);
        test(instructions, expectedDuPairs, expectedDuSequences, expectedDuSequenceWebs);
    }

    // test case represents the instructions from BC_iadd3
    @Test
    public void testDetermineDuPairs3() {
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

        List<DuSequence> expectedDuSequences = new ArrayList<>();
        DuSequence duSequence0 = new DuSequence(duPair6);
        duSequence0.addFirst(duPair0);
        DuSequence duSequence1 = new DuSequence(duPair7);
        duSequence1.addFirst(duPair5);
        DuSequence duSequence2 = new DuSequence(duPair4);
        duSequence2.addFirst(duPair2);
        DuSequence duSequence3 = new DuSequence(duPair3);
        duSequence3.addFirst(duPair1);
        expectedDuSequences.add(duSequence0);
        expectedDuSequences.add(duSequence1);
        expectedDuSequences.add(duSequence2);
        expectedDuSequences.add(duSequence3);

        List<DuSequenceWeb> expectedDuSequenceWebs = new ArrayList<>();
        DuSequenceWeb web0 = new DuSequenceWeb();
        web0.add(duSequence0);
        DuSequenceWeb web1 = new DuSequenceWeb();
        web1.add(duSequence1);
        DuSequenceWeb web2 = new DuSequenceWeb();
        web2.add(duSequence2);
        DuSequenceWeb web3 = new DuSequenceWeb();
        web3.add(duSequence3);
        expectedDuSequenceWebs.add(web0);
        expectedDuSequenceWebs.add(web1);
        expectedDuSequenceWebs.add(web2);
        expectedDuSequenceWebs.add(web3);

        test(instructions, expectedDuPairs, expectedDuSequences, expectedDuSequenceWebs);
    }

    @Test
    public void testDetermineDuPairs4() {
        ArrayList<LIRInstruction> instructions = new ArrayList<>();

        LabelOp labelOp = new LabelOp(null, true);
        labelOp.addIncomingValues(new Value[]{r0.asValue(), rbp.asValue()});
        TestMoveFromReg moveFromRegOp = new TestMoveFromReg(r1.asValue(), r0.asValue());
        TestReturn returnOp = new TestReturn(rbp.asValue(), r1.asValue());

        instructions.add(labelOp);
        instructions.add(moveFromRegOp);
        instructions.add(returnOp);

        List<DuPair> expectedDuPairs = new ArrayList<>();
        DuPair duPair0 = new DuPair(r1.asValue(), moveFromRegOp, returnOp, 0, 1);
        DuPair duPair1 = new DuPair(r0.asValue(), labelOp, moveFromRegOp, 0, 0);
        DuPair duPair2 = new DuPair(rbp.asValue(), labelOp, returnOp, 1, 0);
        expectedDuPairs.add(duPair0);
        expectedDuPairs.add(duPair1);
        expectedDuPairs.add(duPair2);

        List<DuSequence> expectedDuSequences = new ArrayList<>();
        DuSequence duSequence0 = new DuSequence(duPair0);
        duSequence0.addFirst(duPair1);
        expectedDuSequences.add(duSequence0);
        DuSequence duSequence1 = new DuSequence(duPair2);
        expectedDuSequences.add(duSequence1);

        List<DuSequenceWeb> expectedDuSequenceWebs = new ArrayList<>();
        DuSequenceWeb web0 = new DuSequenceWeb();
        web0.add(duSequence0);
        DuSequenceWeb web1 = new DuSequenceWeb();
        web1.add(duSequence1);
        expectedDuSequenceWebs.add(web0);
        expectedDuSequenceWebs.add(web1);

        test(instructions, expectedDuPairs, expectedDuSequences, expectedDuSequenceWebs);
    }

    @Test
    public void testDetermineDuPairsConstants() {
        List<LIRInstruction> instructions = new ArrayList<>();

        LabelOp labelOp = new LabelOp(null, false);
        labelOp.addIncomingValues(new Value[]{rbp.asValue()});
        TestMoveFromConst testMoveFromConst = new TestMoveFromConst(v0, JavaConstant.INT_0);
        TestReturn returnOp = new TestReturn(rbp.asValue(), v0);

        instructions.add(labelOp);
        instructions.add(testMoveFromConst);
        instructions.add(returnOp);

        DuSequenceAnalysis duSequenceAnalysis = new DuSequenceAnalysis();
        Map<Register, DummyRegDef> dummyRegDefs = new HashMap<>();
        Map<Constant, DummyConstDef> dummyConstDefs = new HashMap<>();
        AnalysisResult analysisResult = duSequenceAnalysis.determineDuSequenceWebs(instructions, TestValue.getAttributesMap(), dummyRegDefs, dummyConstDefs);
        assertEquals(true, dummyRegDefs.isEmpty());

        DuPair rbpDuPair = new DuPair(rbp.asValue(), labelOp, returnOp, 0, 0);
        DuPair v0DuPair = new DuPair(v0, testMoveFromConst, returnOp, 0, 1);
        DuPair constDuPair = new DuPair(dummyConstDefs.get(JavaConstant.INT_0).getValue(), dummyConstDefs.get(JavaConstant.INT_0), testMoveFromConst, 0, 0);

        List<DuPair> expectedDuPairs = new ArrayList<>();
        expectedDuPairs.add(rbpDuPair);
        expectedDuPairs.add(v0DuPair);
        expectedDuPairs.add(constDuPair);

        DuSequence rbpDuSequence = new DuSequence(rbpDuPair);
        DuSequence constDuSequence = new DuSequence(v0DuPair);
        constDuSequence.addFirst(constDuPair);

        List<DuSequence> expectedDuSequences = new ArrayList<>();
        expectedDuSequences.add(rbpDuSequence);
        expectedDuSequences.add(constDuSequence);

        DuSequenceWeb rbpDuSequenceWeb = new DuSequenceWeb();
        rbpDuSequenceWeb.add(rbpDuSequence);
        DuSequenceWeb constDuSequenceWeb = new DuSequenceWeb();
        constDuSequenceWeb.add(constDuSequence);

        List<DuSequenceWeb> expectedDuSequenceWebs = new ArrayList<>();
        expectedDuSequenceWebs.add(rbpDuSequenceWeb);
        expectedDuSequenceWebs.add(constDuSequenceWeb);

        test(analysisResult, expectedDuPairs, expectedDuSequences, expectedDuSequenceWebs);
    }

    @Test
    public void testDetermineDuPairsNonAllocatableRegisters() {
        ArrayList<LIRInstruction> instructions = new ArrayList<>();

        LabelOp labelOp = new LabelOp(null, false);
        labelOp.addIncomingValues(new Value[]{rbp.asValue()});
        TestMoveFromReg moveFromReg = new TestMoveFromReg(r0.asValue(), r3.asValue());
        TestReturn returnOp = new TestReturn(rbp.asValue(), r0.asValue());

        instructions.add(labelOp);
        instructions.add(moveFromReg);
        instructions.add(returnOp);

        DuSequenceAnalysis duSequenceAnalysis = new DuSequenceAnalysis();
        AnalysisResult analysisResult = duSequenceAnalysis.determineDuSequenceWebs(instructions, TestValue.getAttributesMap(), new HashMap<>(), new HashMap<>());
        Map<Register, DummyRegDef> dummyDefs = analysisResult.getDummyRegDefs();
        assertEquals(1, dummyDefs.size());
        assertEquals(true, dummyDefs.containsKey(r3));

        DuPair rbpDuPair = new DuPair(rbp.asValue(), labelOp, returnOp, 0, 0);
        DuPair r0DuPair = new DuPair(r0.asValue(), moveFromReg, returnOp, 0, 1);
        DuPair r3DuPair = new DuPair(r3.asValue(), dummyDefs.get(r3), moveFromReg, 0, 0);

        List<DuPair> expectedDuPairs = new ArrayList<>();
        expectedDuPairs.add(rbpDuPair);
        expectedDuPairs.add(r0DuPair);
        expectedDuPairs.add(r3DuPair);

        DuSequence rbpDuSequence = new DuSequence(rbpDuPair);
        DuSequence r0DuSequence = new DuSequence(r0DuPair);
        r0DuSequence.addFirst(r3DuPair);

        List<DuSequence> expectedDuSequences = new ArrayList<>();
        expectedDuSequences.add(rbpDuSequence);
        expectedDuSequences.add(r0DuSequence);

        DuSequenceWeb rbpDuSequenceWeb = new DuSequenceWeb();
        rbpDuSequenceWeb.add(rbpDuSequence);
        DuSequenceWeb r0DuSequenceWeb = new DuSequenceWeb();
        r0DuSequenceWeb.add(r0DuSequence);

        List<DuSequenceWeb> expectedDuSequenceWebs = new ArrayList<>();
        expectedDuSequenceWebs.add(rbpDuSequenceWeb);
        expectedDuSequenceWebs.add(r0DuSequenceWeb);

        test(analysisResult, expectedDuPairs, expectedDuSequences, expectedDuSequenceWebs);
    }

    @Test
    public void testMergeMaps0() {
        Map<Integer, List<Integer>> map1 = new HashMap<>();
        Map<Integer, Map<Integer, List<Integer>>> map = new HashMap<>();
        map.put(3, map1);
        map.put(5, map1);

        Map<Integer, List<Integer>> mergedMap = DuSequenceAnalysis.mergeMaps(map, new Integer[]{3, 5}, null);

        assertEquals(true, mergedMap.entrySet().stream().allMatch(entry -> entry.getValue().isEmpty()));
    }

    @Test
    public void testMergeMaps1() {
        List<Integer> list = Arrays.asList(8, 9);

        Map<Integer, List<Integer>> map1 = new HashMap<>();
        Map<Integer, Map<Integer, List<Integer>>> map = new HashMap<>();
        map1.put(1, list);
        map.put(3, map1);
        map.put(5, new HashMap<>());

        Map<Integer, List<Integer>> mergedMap = DuSequenceAnalysis.mergeMaps(map, new Integer[]{3, 5}, null);

        assertEquals(1, mergedMap.keySet().size());

        List<Integer> actualList = mergedMap.get(1);
        assertNotEquals(null, actualList);
        assertEqualsList(list, actualList);
    }

    @Test
    public void testMergeMaps2() {
        Map<Integer, List<Integer>> map1 = new HashMap<>();
        Map<Integer, List<Integer>> map2 = new HashMap<>();
        map1.put(1, Arrays.asList(10));
        map1.put(2, Arrays.asList(11, 12));
        map1.put(3, Arrays.asList(13, 14));
        map1.put(4, Arrays.asList());
        map2.put(1, Arrays.asList(10));
        map2.put(2, Arrays.asList(11, 12, 15));
        map2.put(4, Arrays.asList(16, 17, 18));

        Map<Integer, Map<Integer, List<Integer>>> map = new HashMap<>();
        map.put(1, map1);
        map.put(2, map2);
        Map<Integer, List<Integer>> mergedMap = DuSequenceAnalysis.mergeMaps(map, new Integer[]{1, 2}, null);
        assertEqualsList(Arrays.asList(10), mergedMap.get(1));
        assertEqualsList(Arrays.asList(11, 12, 15), mergedMap.get(2));
        assertEqualsList(Arrays.asList(13, 14), mergedMap.get(3));
        assertEqualsList(Arrays.asList(16, 17, 18), mergedMap.get(4));
    }

    @Test
    public void testSARAVerifyValue() {
        SARAVerifyValueComparator comparator = new SARAVerifyValueComparator();

        // register
        assertEquals(0, comparator.compare(r1.asValue(), r1.asValue()));
        assertEquals(-1, comparator.compare(r0.asValue(), r1.asValue()));
        assertEquals(1, comparator.compare(r1.asValue(), r0.asValue()));
        assertEquals(2, comparator.compare(r2.asValue(), r0.asValue()));
        assertEquals(-2, comparator.compare(r0.asValue(), r2.asValue()));

        // variable
        assertEquals(0, comparator.compare(v1, v1));
        assertEquals(-1, comparator.compare(v1, v2));
        assertEquals(1, comparator.compare(v2, v1));
        assertEquals(2, comparator.compare(v3, v1));
        assertEquals(3, comparator.compare(v3, v0));
        assertEquals(-3, comparator.compare(v0, v3));

        // constant value
        ConstantValue c0 = new ConstantValue(ValueKind.Illegal, JavaConstant.INT_0);
        ConstantValue c1 = new ConstantValue(ValueKind.Illegal, JavaConstant.INT_1);
        ConstantValue c5 = new ConstantValue(ValueKind.Illegal, JavaConstant.forInt(5));
        ConstantValue cMinus1 = new ConstantValue(ValueKind.Illegal, JavaConstant.INT_MINUS_1);
        assertEquals(0, comparator.compare(c1, c1));
        assertEquals(1, comparator.compare(c1, c0));
        assertEquals(-1, comparator.compare(c0, c1));
        assertEquals(4, comparator.compare(c5, c1));
        assertEquals(-4, comparator.compare(c1, c5));
        assertEquals(-2, comparator.compare(cMinus1, c1));
        assertEquals(2, comparator.compare(c1, cMinus1));

        ConstantValue cTrue = new ConstantValue(ValueKind.Illegal, JavaConstant.TRUE);
        ConstantValue cFalse = new ConstantValue(ValueKind.Illegal, JavaConstant.FALSE);
        assertEquals(0, comparator.compare(cTrue, cTrue));
        assertEquals(0, comparator.compare(cFalse, cFalse));
        assertEquals(1, comparator.compare(cTrue, cFalse));
        assertEquals(-1, comparator.compare(cFalse, cTrue));

        ConstantValue cNull1 = new ConstantValue(ValueKind.Illegal, JavaConstant.NULL_POINTER);
        ConstantValue cNull2 = new ConstantValue(ValueKind.Illegal, JavaConstant.NULL_POINTER);
        assertEquals(0, comparator.compare(cNull1, cNull2));

        ConstantValue c0L = new ConstantValue(ValueKind.Illegal, JavaConstant.LONG_0);
        ConstantValue c1L = new ConstantValue(ValueKind.Illegal, JavaConstant.LONG_1);
        ConstantValue c4L = new ConstantValue(ValueKind.Illegal, JavaConstant.forLong(4L));
        assertEquals(0, comparator.compare(c0L, c0L));
        assertEquals(0, comparator.compare(c1L, c1L));
        assertEquals(0, comparator.compare(c4L, c4L));
        assertEquals(-1, comparator.compare(c0L, c1L));
        assertEquals(1, comparator.compare(c1L, c0L));
        assertEquals(1, comparator.compare(c4L, c0L));
        assertEquals(-1, comparator.compare(c0L, c4L));
        assertEquals(-1, comparator.compare(c0L, c1L));
        assertEquals(1, comparator.compare(c1L, c0L));

        ConstantValue c1F = new ConstantValue(ValueKind.Illegal, JavaConstant.FLOAT_1);
        ConstantValue c5F = new ConstantValue(ValueKind.Illegal, JavaConstant.forFloat(5.0F));
        ConstantValue c05F = new ConstantValue(ValueKind.Illegal, JavaConstant.forFloat(0.5F));
        assertEquals(0, comparator.compare(c1F, c1F));
        assertEquals(0, comparator.compare(c5F, c5F));
        assertEquals(0, comparator.compare(c05F, c05F));
        assertEquals(-1, comparator.compare(c1F, c5F));
        assertEquals(1, comparator.compare(c5F, c1F));
        assertEquals(-1, comparator.compare(c05F, c1F));
        assertEquals(1, comparator.compare(c1F, c05F));

        ConstantValue c1D = new ConstantValue(ValueKind.Illegal, JavaConstant.DOUBLE_1);
        ConstantValue c5D = new ConstantValue(ValueKind.Illegal, JavaConstant.forDouble(5.0D));
        ConstantValue c05D = new ConstantValue(ValueKind.Illegal, JavaConstant.forDouble(0.5D));
        assertEquals(0, comparator.compare(c1D, c1D));
        assertEquals(0, comparator.compare(c5D, c5D));
        assertEquals(0, comparator.compare(c05D, c05D));
        assertEquals(-1, comparator.compare(c1D, c5D));
        assertEquals(1, comparator.compare(c5D, c1D));
        assertEquals(-1, comparator.compare(c05D, c1D));
        assertEquals(1, comparator.compare(c1D, c05D));

        // stack slot
        StackSlot s1 = StackSlot.get(ValueKind.Illegal, -16, true);
        StackSlot s2 = StackSlot.get(ValueKind.Illegal, -16, true);
        StackSlot s3 = StackSlot.get(ValueKind.Illegal, 16, true);
        StackSlot s4 = StackSlot.get(ValueKind.Illegal, 32, false);
        StackSlot s5 = StackSlot.get(ValueKind.Illegal, 32, false);
        StackSlot s6 = StackSlot.get(ValueKind.Illegal, -24, true);
        assertEquals(0, comparator.compare(s1, s1));
        assertEquals(0, comparator.compare(s1, s2));
        assertEquals(0, comparator.compare(s2, s1));
        assertEquals(-32, comparator.compare(s1, s3));
        assertEquals(1, comparator.compare(s1, s4));
        assertEquals(-1, comparator.compare(s4, s1));
        assertEquals(0, comparator.compare(s4, s5));
        assertEquals(0, comparator.compare(s5, s4));
        assertEquals(8, comparator.compare(s1, s6));
        assertEquals(-8, comparator.compare(s6, s1));
        assertEquals(-1, comparator.compare(s5, s6));
        assertEquals(1, comparator.compare(s6, s5));

        // virtual stack slot
        SimpleVirtualStackSlot vs0 = new SimpleVirtualStackSlot(0, ValueKind.Illegal);
        SimpleVirtualStackSlot vs1 = new SimpleVirtualStackSlot(1, ValueKind.Illegal);
        SimpleVirtualStackSlot vs2 = new SimpleVirtualStackSlot(2, ValueKind.Illegal);
        SimpleVirtualStackSlot vsMinus1 = new SimpleVirtualStackSlot(-1, ValueKind.Illegal);
        assertEquals(0, comparator.compare(vs0, vs0));
        assertEquals(0, comparator.compare(vs1, vs1));
        assertEquals(0, comparator.compare(vs2, vs2));
        assertEquals(-1, comparator.compare(vs0, vs1));
        assertEquals(1, comparator.compare(vs1, vs0));
        assertEquals(-2, comparator.compare(vs0, vs2));
        assertEquals(2, comparator.compare(vs2, vs0));
        assertEquals(2, comparator.compare(vs1, vsMinus1));
        assertEquals(-2, comparator.compare(vsMinus1, vs1));

        // mixed types
        assertEquals(-1, comparator.compare(r0.asValue(), v1));
        assertEquals(1, comparator.compare(v0, r2.asValue()));
        assertEquals(-2, comparator.compare(r0.asValue(), c1));
        assertEquals(2, comparator.compare(c1, r0.asValue()));
        assertEquals(-1, comparator.compare(v0, c1));
        assertEquals(1, comparator.compare(c1, v0));
        assertEquals(1, comparator.compare(s1, c1));
        assertEquals(-1, comparator.compare(c1, s1));
        assertEquals(2, comparator.compare(s1, v0));
        assertEquals(-2, comparator.compare(v0, s1));
        assertEquals(3, comparator.compare(s1, r0.asValue()));
        assertEquals(-3, comparator.compare(r0.asValue(), s1));
        assertEquals(-1, comparator.compare(s2, vs1));
        assertEquals(1, comparator.compare(vs1, s2));
        assertEquals(-2, comparator.compare(c1, vs1));
        assertEquals(2, comparator.compare(vs1, c1));
        assertEquals(-3, comparator.compare(v0, vs1));
        assertEquals(3, comparator.compare(vs2, v0));
        assertEquals(-4, comparator.compare(r2.asValue(), vs1));
        assertEquals(4, comparator.compare(vs1, r2.asValue()));

    }

    private static void test(ArrayList<LIRInstruction> instructions, Map<Value, List<Node>> expectedDuSequenceWebs) {
        DuSequenceAnalysis duSequenceAnalysis = new DuSequenceAnalysis();

        AnalysisResult analysisResult = duSequenceAnalysis.determineDuSequenceWebs(instructions, TestValue.getAttributesMap(), new HashMap<>(), new HashMap<>());
        test(analysisResult, expectedDuSequenceWebs);
    }

    private static void test(AnalysisResult analysisResult, Map<Value, List<Node>> expectedDuSequenceWebs) {
        Map<Value, List<Node>> actualDuSequenceWebs = analysisResult.getDuSequenceWebs();
        assertEquals("The number of key-value pairs does not match.", expectedDuSequenceWebs.size(), actualDuSequenceWebs.size());

        for (Entry<Value, List<Node>> entry : expectedDuSequenceWebs.entrySet()) {
            List<Node> expectedNodes = entry.getValue();
            List<Node> actualNodes = actualDuSequenceWebs.get(entry.getKey());

            assertNodes(expectedNodes, actualNodes);
        }
    }

    private static void assertNodes(List<Node> expectedNodes, List<Node> actualNodes) {
        assertEquals("The number of nodes does not match.", expectedNodes.size(), actualNodes.size());

        for (Node expectedNode : expectedNodes) {
            Node actualNode = actualNodes.stream().filter(node -> node.equals(expectedNode)).findAny().get();
            assertNotEquals("No actual node found for expected node: " + expectedNode, null, actualNode);

            if (expectedNode instanceof DefNode && actualNode instanceof DefNode) {
                DefNode expectedDefNode = (DefNode) expectedNode;
                DefNode actualDefNode = (DefNode) actualNode;
                assertNodes(expectedDefNode.getNextNodes(), actualDefNode.getNextNodes());
            } else if (expectedNode instanceof MoveNode && actualNode instanceof MoveNode) {
                MoveNode expectedMoveNode = (MoveNode) expectedNode;
                MoveNode actualMoveNode = (MoveNode) expectedNode;
                assertNodes(expectedMoveNode.getNextNodes(), actualMoveNode.getNextNodes());
            } else {
                assertEquals("The types of the actual node and the expected node do not match.", true, expectedNode instanceof UseNode && actualNode instanceof UseNode);
            }
        }
    }

    private static <T> void assertEqualsList(List<T> expected, List<T> actual) {
        assertEquals("The number of elements in the list does not match.", expected.size(), actual.size());

        assert actual.containsAll(expected);
    }
}
