package org.graalvm.compiler.lir.saraverify;

import java.util.Comparator;

import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.lir.LIRValueUtil;
import org.graalvm.compiler.lir.Variable;
import org.graalvm.compiler.lir.VirtualStackSlot;

import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.StackSlot;
import jdk.vm.ci.code.ValueUtil;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.Value;

public class SARAVerifyValueComparator implements Comparator<Value> {

    enum ValueType {
        Register,
        Variable,
        JavaConstantValue,
        StackSlot,
        VirtualStackSlot
    }

    @Override
    public int compare(Value o1, Value o2) {
        if (ValueUtil.isRegister(o1) && ValueUtil.isRegister(o2)) {
            Register r1 = ValueUtil.asRegister(o1);
            Register r2 = ValueUtil.asRegister(o2);
            return r1.number - r2.number;
        }

        if (LIRValueUtil.isVariable(o1) && LIRValueUtil.isVariable(o2)) {
            Variable v1 = LIRValueUtil.asVariable(o1);
            Variable v2 = LIRValueUtil.asVariable(o2);
            return v1.index - v2.index;
        }

        if (LIRValueUtil.isJavaConstant(o1) && LIRValueUtil.isJavaConstant(o2)) {
            JavaConstant c1 = LIRValueUtil.asJavaConstant(o1);
            JavaConstant c2 = LIRValueUtil.asJavaConstant(o2);

            return compareJavaConstant(c1, c2);
        }

        if (ValueUtil.isStackSlot(o1) && ValueUtil.isStackSlot(o2)) {
            StackSlot s1 = ValueUtil.asStackSlot(o1);
            StackSlot s2 = ValueUtil.asStackSlot(o2);

            int addFrameSizeComparison = Boolean.compare(s1.getRawAddFrameSize(), s2.getRawAddFrameSize());

            if (addFrameSizeComparison == 0) {
                return s1.getRawOffset() - s2.getRawOffset();
            } else {
                return addFrameSizeComparison;
            }
        }

        if (LIRValueUtil.isVirtualStackSlot(o1) && LIRValueUtil.isVirtualStackSlot(o2)) {
            VirtualStackSlot vs1 = LIRValueUtil.asVirtualStackSlot(o1);
            VirtualStackSlot vs2 = LIRValueUtil.asVirtualStackSlot(o2);

            return vs1.getId() - vs2.getId();
        }

        ValueType valType1 = getValueType(o1);
        ValueType valType2 = getValueType(o2);

        return valType1.ordinal() - valType2.ordinal();
    }

    private static ValueType getValueType(Value value) {
        if (ValueUtil.isRegister(value)) {
            return ValueType.Register;
        }

        if (LIRValueUtil.isVariable(value)) {
            return ValueType.Variable;
        }

        if (LIRValueUtil.isJavaConstant(value)) {
            return ValueType.JavaConstantValue;
        }

        if (ValueUtil.isStackSlot(value)) {
            return ValueType.StackSlot;
        }

        if (LIRValueUtil.isVirtualStackSlot(value)) {
            return ValueType.VirtualStackSlot;
        }

        throw GraalError.unimplemented("Value compare not implemented for " + value.getClass());
    }

    private static int compareJavaConstant(JavaConstant c1, JavaConstant c2) {
        if (!c1.getJavaKind().equals(c2.getJavaKind())) {
            JavaKind javaKind1 = c1.getJavaKind();
            JavaKind javaKind2 = c2.getJavaKind();
            return javaKind1.ordinal() - javaKind2.ordinal();
        }

        if (c1.getJavaKind().equals(JavaKind.Int) && c2.getJavaKind().equals(JavaKind.Int)) {
            return c1.asInt() - c2.asInt();
        }

        if (c1.getJavaKind().equals(JavaKind.Boolean) && c2.getJavaKind().equals(JavaKind.Boolean)) {
            return Boolean.compare(c1.asBoolean(), c2.asBoolean());
        }

        if (c1.getJavaKind().equals(JavaKind.Long) && c2.getJavaKind().equals(JavaKind.Long)) {
            return Long.compare(c1.asLong(), c2.asLong());
        }

        if (c1.getJavaKind().equals(JavaKind.Float) && c2.getJavaKind().equals(JavaKind.Float)) {
            return Float.compare(c1.asFloat(), c2.asFloat());
        }

        if (c1.getJavaKind().equals(JavaKind.Double) && c2.getJavaKind().equals(JavaKind.Double)) {
            return Double.compare(c1.asDouble(), c2.asDouble());
        }

        if (c1.equals(JavaConstant.NULL_POINTER) && c2.equals(JavaConstant.NULL_POINTER)) {
            return 0;
        }

        throw GraalError.unimplemented("JavaConstant compare not implemented between " + c1.getJavaKind() + " and " + c2.getJavaKind());
    }

}
