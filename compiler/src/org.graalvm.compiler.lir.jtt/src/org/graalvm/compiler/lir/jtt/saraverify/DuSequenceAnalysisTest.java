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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
import org.graalvm.compiler.lir.saraverify.DuSequenceAnalysis;
import org.graalvm.compiler.lir.saraverify.DuSequenceAnalysis.DummyConstDef;
import org.graalvm.compiler.lir.saraverify.DuSequenceAnalysis.DummyRegDef;
import org.graalvm.compiler.lir.saraverify.MoveNode;
import org.graalvm.compiler.lir.saraverify.Node;
import org.graalvm.compiler.lir.saraverify.SARAVerifyUtil;
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

    @SuppressWarnings("unchecked")
    public static <T> Set<T> asSet(T... values) {
        return Stream.of(values).collect(Collectors.toSet());
    }

    @Rule public ExpectedException thrown = ExpectedException.none();

    @Test
    public void testDetermineDuSequences0() {
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

        DefNode defNodeLabel0 = new DefNode(r0.asValue(), labelOp, 0);
        DefNode defNodeLabel1 = new DefNode(r1.asValue(), labelOp, 1);
        DefNode defNodeLabel2 = new DefNode(rbp.asValue(), labelOp, 2);

        DefNode defNodeAdd00 = new DefNode(r2.asValue(), addOp, 0);
        UseNode useNodeAdd00 = new UseNode(r0.asValue(), addOp, 0);
        UseNode useNodeAdd01 = new UseNode(r1.asValue(), addOp, 1);

        DefNode defNodeAdd10 = new DefNode(r2.asValue(), addOp2, 0);
        UseNode useNodeAdd10 = new UseNode(r1.asValue(), addOp2, 0);
        UseNode useNodeAdd11 = new UseNode(r2.asValue(), addOp2, 1);

        UseNode useNodeReturn0 = new UseNode(rbp.asValue(), returnOp, 0);
        UseNode useNodeReturn1 = new UseNode(r2.asValue(), returnOp, 1);

        defNodeLabel0.addNextNodes(useNodeAdd00);
        defNodeLabel1.addNextNodes(useNodeAdd01);
        defNodeLabel1.addNextNodes(useNodeAdd10);
        defNodeLabel2.addNextNodes(useNodeReturn0);
        defNodeAdd00.addNextNodes(useNodeAdd11);
        defNodeAdd10.addNextNodes(useNodeReturn1);

        Set<Node> r0Nodes = asSet(defNodeLabel0);
        Set<Node> r1Nodes = asSet(defNodeLabel1);
        Set<Node> r2Nodes = asSet(defNodeAdd00, defNodeAdd10);
        Set<Node> rbpNodes = asSet(defNodeLabel2);

        Map<Value, Set<Node>> expectedDuSequences = new HashMap<>();
        expectedDuSequences.put(r0.asValue(), r0Nodes);
        expectedDuSequences.put(r1.asValue(), r1Nodes);
        expectedDuSequences.put(r2.asValue(), r2Nodes);
        expectedDuSequences.put(rbp.asValue(), rbpNodes);

        test(instructions, expectedDuSequences);
    }

    @Test
    public void testDetermineDuSequences1() {
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

        Map<Value, Set<Node>> expectedDuSequences = new HashMap<>();
        Set<Node> r0Nodes = asSet(defNodeLabel0);

        Set<Node> rbpNodes = asSet(defNodeLabel2);

        expectedDuSequences.put(r0.asValue(), r0Nodes);
        expectedDuSequences.put(rbp.asValue(), rbpNodes);

        test(instructions, expectedDuSequences);
    }

    @Test
    public void testDetermineDuSequences2() {
        ArrayList<LIRInstruction> instructions = new ArrayList<>();

        LabelOp labelOp = new LabelOp(null, true);
        labelOp.addIncomingValues(new Value[]{r0.asValue(), rbp.asValue()});
        TestReturn returnOp = new TestReturn(rbp.asValue(), r2.asValue());

        instructions.add(labelOp);
        instructions.add(returnOp);

        DefNode defNodeLabel1 = new DefNode(rbp.asValue(), labelOp, 1);
        UseNode useNodeLabel0 = new UseNode(rbp.asValue(), returnOp, 0);
        defNodeLabel1.addNextNodes(useNodeLabel0);

        Set<Node> rbpNodes = asSet(defNodeLabel1);

        Map<Value, Set<Node>> expectedDuSequences = new HashMap<>();
        expectedDuSequences.put(rbp.asValue(), rbpNodes);

        thrown.expect(GraalError.class);
        test(instructions, expectedDuSequences);
    }

    // test case represents the instructions from BC_iadd3
    @Test
    public void testDetermineDuSequences3() {
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

        DefNode defNodeLabel0 = new DefNode(r0.asValue(), i0, 0);
        DefNode defNodeLabel1 = new DefNode(r1.asValue(), i0, 1);
        DefNode defNodeLabel2 = new DefNode(rbp.asValue(), i0, 2);
        MoveNode moveNodei1 = new MoveNode(v3, rbp.asValue(), i1, 0, 0);
        MoveNode moveNodei2 = new MoveNode(v0, r0.asValue(), i2, 0, 0);
        MoveNode moveNodei3 = new MoveNode(v1, r1.asValue(), i3, 0, 0);
        DefNode defNodeAdd0 = new DefNode(v2, i4, 0);
        UseNode useNodeAdd0 = new UseNode(v0, i4, 0);
        UseNode useNodeAdd1 = new UseNode(v1, i4, 1);
        MoveNode moveNodei5 = new MoveNode(rax.asValue(), v2, i5, 0, 0);
        UseNode useNodeReturn0 = new UseNode(v3, i6, 0);
        UseNode useNodeReturn1 = new UseNode(rax.asValue(), i6, 1);

        defNodeLabel0.addNextNodes(moveNodei2);
        moveNodei2.addNextNodes(useNodeAdd0);

        defNodeLabel1.addNextNodes(moveNodei3);
        moveNodei3.addNextNodes(useNodeAdd1);

        defNodeLabel2.addNextNodes(moveNodei1);
        moveNodei1.addNextNodes(useNodeReturn1);

        defNodeAdd0.addNextNodes(moveNodei5);
        moveNodei5.addNextNodes(useNodeReturn0);

        Map<Value, Set<Node>> expectedDuSequences = new HashMap<>();
        expectedDuSequences.put(r0.asValue(), asSet(defNodeLabel0));
        expectedDuSequences.put(r1.asValue(), asSet(defNodeLabel1));
        expectedDuSequences.put(rbp.asValue(), asSet(defNodeLabel2));
        expectedDuSequences.put(v2, asSet(defNodeAdd0));

        test(instructions, expectedDuSequences);
    }

    @Test
    public void testDetermineDuSequences4() {
        ArrayList<LIRInstruction> instructions = new ArrayList<>();

        LabelOp labelOp = new LabelOp(null, true);
        labelOp.addIncomingValues(new Value[]{r0.asValue(), rbp.asValue()});
        TestMoveFromReg moveFromRegOp = new TestMoveFromReg(r1.asValue(), r0.asValue());
        TestReturn returnOp = new TestReturn(rbp.asValue(), r1.asValue());

        instructions.add(labelOp);
        instructions.add(moveFromRegOp);
        instructions.add(returnOp);

        DefNode defNodeLabel0 = new DefNode(r0.asValue(), labelOp, 0);
        DefNode defNodeLabel1 = new DefNode(rbp.asValue(), labelOp, 1);
        MoveNode moveNode = new MoveNode(r1.asValue(), r0.asValue(), moveFromRegOp, 0, 0);
        UseNode useNodeReturn0 = new UseNode(rbp.asValue(), returnOp, 0);
        UseNode useNodeReturn1 = new UseNode(r1.asValue(), returnOp, 1);

        defNodeLabel0.addNextNodes(moveNode);
        moveNode.addNextNodes(useNodeReturn1);

        defNodeLabel1.addNextNodes(useNodeReturn0);

        Map<Value, Set<Node>> expectedDuSequences = new HashMap<>();
        expectedDuSequences.put(r0.asValue(), asSet(defNodeLabel0));
        expectedDuSequences.put(rbp.asValue(), asSet(defNodeLabel1));

        test(instructions, expectedDuSequences);
    }

    @Test
    public void testDetermineDuSequencesConstants() {
        List<LIRInstruction> instructions = new ArrayList<>();
        ConstantValue constantValue0 = new ConstantValue(ValueKind.Illegal, JavaConstant.INT_0);

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
        AnalysisResult analysisResult = duSequenceAnalysis.determineDuSequences(instructions, TestValue.getAttributesMap(), dummyRegDefs, dummyConstDefs);
        assertEquals(true, dummyRegDefs.isEmpty());

        DefNode defNodeLabel = new DefNode(rbp.asValue(), labelOp, 0);
        DefNode defNodeConstant = new DefNode(constantValue0, dummyConstDefs.get(JavaConstant.INT_0), 0);
        MoveNode moveNode = new MoveNode(v0, constantValue0, testMoveFromConst, 0, 0);
        UseNode useNodeReturn0 = new UseNode(rbp.asValue(), returnOp, 0);
        UseNode useNodeReturn1 = new UseNode(v0, returnOp, 1);

        defNodeLabel.addNextNodes(useNodeReturn0);
        defNodeConstant.addNextNodes(moveNode);
        moveNode.addNextNodes(useNodeReturn1);

        Map<Value, Set<Node>> expectedDuSequences = new HashMap<>();
        expectedDuSequences.put(rbp.asValue(), asSet(defNodeLabel));
        expectedDuSequences.put(constantValue0, asSet(defNodeConstant));

        test(analysisResult, expectedDuSequences);
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
        AnalysisResult analysisResult = duSequenceAnalysis.determineDuSequences(instructions, TestValue.getAttributesMap(), new HashMap<>(), new HashMap<>());
        Map<Register, DummyRegDef> dummyDefs = analysisResult.getDummyRegDefs();
        assertEquals(1, dummyDefs.size());
        assertEquals(true, dummyDefs.containsKey(r3));

        DefNode defNodeLabel = new DefNode(rbp.asValue(), labelOp, 0);
        DefNode defNodeR3 = new DefNode(r3.asValue(), dummyDefs.get(r3), 0);
        MoveNode moveNode = new MoveNode(r0.asValue(), r3.asValue(), moveFromReg, 0, 0);
        UseNode useNodeReturn0 = new UseNode(rbp.asValue(), returnOp, 0);
        UseNode useNodeReturn1 = new UseNode(r0.asValue(), returnOp, 1);

        defNodeLabel.addNextNodes(useNodeReturn0);
        defNodeR3.addNextNodes(moveNode);
        moveNode.addNextNodes(useNodeReturn1);

        Map<Value, Set<Node>> expectedDuSequenceWebs = new HashMap<>();
        expectedDuSequenceWebs.put(rbp.asValue(), asSet(defNodeLabel));
        expectedDuSequenceWebs.put(r3.asValue(), asSet(defNodeR3));

        test(analysisResult, expectedDuSequenceWebs);
    }

    @Test
    public void testMergeMaps0() {
        Map<Value, Set<Integer>> map1 = new HashMap<>();
        Map<Integer, Map<Value, Set<Integer>>> map = new HashMap<>();
        map.put(3, map1);
        map.put(5, map1);

        Map<Value, Set<Integer>> mergedMap = SARAVerifyUtil.mergeMaps(map, new Integer[]{3, 5});

        assertEquals(true, mergedMap.entrySet().stream().allMatch(entry -> entry.getValue().isEmpty()));
    }

    @Test
    public void testMergeMaps1() {
        Set<Integer> set = asSet(8, 9);

        Map<Value, Set<Integer>> map1 = new HashMap<>();
        Map<Integer, Map<Value, Set<Integer>>> map = new HashMap<>();
        map1.put(r0.asValue(), set);
        map.put(3, map1);
        map.put(5, new HashMap<>());

        Map<Value, Set<Integer>> mergedMap = SARAVerifyUtil.mergeMaps(map, new Integer[]{3, 5});

        assertEquals(1, mergedMap.keySet().size());

        Set<Integer> actualList = mergedMap.get(r0.asValue());
        assertNotEquals(null, actualList);
        assertEquals(true, actualList.equals(set));
    }

    @Test
    public void testMergeMaps2() {
        Map<Value, Set<Integer>> map1 = new HashMap<>();
        Map<Value, Set<Integer>> map2 = new HashMap<>();
        map1.put(r1.asValue(), asSet(10));
        map1.put(r2.asValue(), asSet(11, 12));
        map1.put(r3.asValue(), asSet(13, 14));
        map1.put(rax.asValue(), asSet());
        map2.put(r1.asValue(), asSet(10));
        map2.put(r2.asValue(), asSet(11, 12, 15));
        map2.put(rax.asValue(), asSet(16, 17, 18));

        Map<Integer, Map<Value, Set<Integer>>> map = new HashMap<>();
        map.put(1, map1);
        map.put(2, map2);
        Map<Value, Set<Integer>> mergedMap = SARAVerifyUtil.mergeMaps(map, new Integer[]{1, 2});
        assertEquals(true, mergedMap.get(r1.asValue()).equals(asSet(10)));
        assertEquals(true, mergedMap.get(r2.asValue()).equals(asSet(11, 12, 15)));
        assertEquals(true, mergedMap.get(r3.asValue()).equals(asSet(13, 14)));
        assertEquals(true, mergedMap.get(rax.asValue()).equals(asSet(16, 17, 18)));
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

    private static void test(ArrayList<LIRInstruction> instructions, Map<Value, Set<Node>> expectedDuSequences) {
        DuSequenceAnalysis duSequenceAnalysis = new DuSequenceAnalysis();

        AnalysisResult analysisResult = duSequenceAnalysis.determineDuSequences(instructions, TestValue.getAttributesMap(), new HashMap<>(), new HashMap<>());
        test(analysisResult, expectedDuSequences);
    }

    private static void test(AnalysisResult analysisResult, Map<Value, Set<Node>> expectedDuSequences) {
        Map<Value, Set<DefNode>> actualDuSequences = analysisResult.getDuSequences();
        assertEquals("The number of key-value pairs does not match.", expectedDuSequences.size(), actualDuSequences.size());

        for (Entry<Value, Set<Node>> entry : expectedDuSequences.entrySet()) {
            Set<Node> expectedNodes = entry.getValue();
            Set<DefNode> actualNodes = actualDuSequences.get(entry.getKey());

            assertNodes(expectedNodes, actualNodes);
        }
    }

    private static void assertNodes(Set<Node> expectedNodes, Set<DefNode> actualNodes) {
        assertEquals("The number of nodes does not match.", expectedNodes.size(), actualNodes.size());

        for (Node expectedNode : expectedNodes) {
            DefNode actualNode = actualNodes.stream().filter(node -> node.equals(expectedNode)).findAny().get();
            assertNotEquals("No actual node found for expected node: " + expectedNode, null, actualNode);

            DefNode expectedDefNode = (DefNode) expectedNode;
            assertNextNodes(expectedDefNode.getNextNodes(), actualNode.getNextNodes());

        }
    }

    private static void assertNextNodes(Set<Node> expectedNodes, Set<Node> actualNodes) {
        assertEquals("The number of nodes does not match.", expectedNodes.size(), actualNodes.size());

        for (Node expectedNode : expectedNodes) {
            Node actualNode = actualNodes.stream().filter(node -> node.equals(expectedNode)).findAny().get();
            assertNotEquals("No actual node found for expected node: " + expectedNode, null, actualNode);

            if (expectedNode.isDefNode() && actualNode.isDefNode()) {
                DefNode expectedDefNode = (DefNode) expectedNode;
                DefNode actualDefNode = (DefNode) actualNode;
                assertNextNodes(expectedDefNode.getNextNodes(), actualDefNode.getNextNodes());
            } else if (expectedNode instanceof MoveNode && actualNode instanceof MoveNode) {
                MoveNode expectedMoveNode = (MoveNode) expectedNode;
                MoveNode actualMoveNode = (MoveNode) expectedNode;
                assertNextNodes(expectedMoveNode.getNextNodes(), actualMoveNode.getNextNodes());
            } else {
                assertEquals("The types of the actual node and the expected node do not match.", true, expectedNode instanceof UseNode && actualNode instanceof UseNode);
            }
        }
    }
}
