package org.graalvm.compiler.lir.jtt.saraverify;

import static org.graalvm.compiler.lir.LIRInstruction.OperandFlag.CONST;
import static org.graalvm.compiler.lir.LIRInstruction.OperandFlag.HINT;
import static org.graalvm.compiler.lir.LIRInstruction.OperandFlag.ILLEGAL;
import static org.graalvm.compiler.lir.LIRInstruction.OperandFlag.REG;
import static org.graalvm.compiler.lir.LIRInstruction.OperandFlag.STACK;

import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.LIRInstructionClass;
import org.graalvm.compiler.lir.Opcode;
import org.graalvm.compiler.lir.StandardOp.LoadConstantOp;
import org.graalvm.compiler.lir.StandardOp.ValueMoveOp;
import org.graalvm.compiler.lir.Variable;
import org.graalvm.compiler.lir.asm.CompilationResultBuilder;

import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.Value;

public class TestOp {
    public static class TestDef extends LIRInstruction {

        public static final LIRInstructionClass<TestDef> TYPE = LIRInstructionClass.create(TestDef.class);
        @Def protected AllocatableValue value;

        public TestDef(AllocatableValue value) {
            super(TYPE);
            this.value = value;
        }

        public void setValue(AllocatableValue value) {
            this.value = value;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb) {
        }
    }

    public static class TestMoveFromReg extends LIRInstruction implements ValueMoveOp {
        public static final LIRInstructionClass<TestMoveFromReg> TYPE = LIRInstructionClass.create(TestMoveFromReg.class);

        @Def({REG, STACK}) protected AllocatableValue result;
        @Use({REG, HINT}) protected AllocatableValue input;

        public TestMoveFromReg(AllocatableValue result, AllocatableValue input) {
            super(TYPE);
            this.result = result;
            this.input = input;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb) {
        }

        @Override
        public AllocatableValue getResult() {
            return result;
        }

        @Override
        public AllocatableValue getInput() {
            return input;
        }
    }

    public static class TestMoveToReg extends LIRInstruction implements ValueMoveOp {
        public static final LIRInstructionClass<TestMoveToReg> TYPE = LIRInstructionClass.create(TestMoveToReg.class);

        @Def({REG, HINT}) protected AllocatableValue result;
        @Use({REG, STACK}) protected AllocatableValue input;

        public TestMoveToReg(AllocatableValue result, AllocatableValue input) {
            super(TYPE);
            this.result = result;
            this.input = input;
        }

        @Override
        public AllocatableValue getResult() {
            return result;
        }

        @Override
        public AllocatableValue getInput() {
            return input;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb) {
        }

    }

    public static class TestMoveFromConst extends LIRInstruction implements LoadConstantOp {

        public static final LIRInstructionClass<TestMoveFromConst> TYPE = LIRInstructionClass.create(TestMoveFromConst.class);

        @Def({REG, STACK}) protected AllocatableValue result;
        private final JavaConstant input;

        public TestMoveFromConst(AllocatableValue result, JavaConstant input) {
            super(TYPE);
            this.result = result;
            this.input = input;
        }

        @Override
        public AllocatableValue getResult() {
            return result;
        }

        @Override
        public Constant getConstant() {
            return input;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb) {
        }
    }

    public static class TestCondMove extends LIRInstruction {
        public static final LIRInstructionClass<TestCondMove> TYPE = LIRInstructionClass.create(TestCondMove.class);

        @Def({REG, HINT}) protected Value result;
        @Alive({REG}) protected Value trueValue;
        @Use({REG, STACK, CONST}) protected Value falseValue;

        public TestCondMove(Variable result, AllocatableValue trueValue, Value falseValue) {
            super(TYPE);
            this.result = result;
            this.trueValue = trueValue;
            this.falseValue = falseValue;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb) {
        }
    }

    public static class TestBinary extends LIRInstruction {
        public enum ArithmeticOpcode {
            ADD,
            SUB,
            MUL,
            DIV;
        }

        public static final LIRInstructionClass<TestBinary> TYPE = LIRInstructionClass.create(TestBinary.class);

        @Opcode protected final ArithmeticOpcode opcode;
        @Def protected AllocatableValue result;
        @Use protected AllocatableValue x;
        @Use protected AllocatableValue y;

        public TestBinary(ArithmeticOpcode opcode, AllocatableValue result, AllocatableValue x, AllocatableValue y) {
            super(TYPE);
            this.opcode = opcode;
            this.result = result;
            this.x = x;
            this.y = y;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb) {
        }
    }

    public static class TestBinaryConsumerConst extends LIRInstruction {

        public static final LIRInstructionClass<TestBinaryConsumerConst> TYPE = LIRInstructionClass.create(TestBinaryConsumerConst.class);

        @Use({REG, STACK}) protected AllocatableValue x;
        private final int y;

        public TestBinaryConsumerConst(AllocatableValue x, int y) {
            super(TYPE);
            this.x = x;
            this.y = y;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb) {
        }

        public int getY() {
            return y;
        }
    }

    public static class TestReturn extends LIRInstruction {

        public static final LIRInstructionClass<TestReturn> TYPE = LIRInstructionClass.create(TestReturn.class);

        @Use({REG, LIRInstruction.OperandFlag.STACK}) protected Value savedRbp;
        @Use({REG, ILLEGAL}) protected Value value;

        public TestReturn(AllocatableValue savedRbp, AllocatableValue value) {
            super(TYPE);
            this.savedRbp = savedRbp;
            this.value = value;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb) {
        }
    }
}