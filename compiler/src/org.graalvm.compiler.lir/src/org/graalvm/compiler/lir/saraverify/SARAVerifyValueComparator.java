package org.graalvm.compiler.lir.saraverify;

import java.util.Comparator;

import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.lir.LIRValueUtil;
import org.graalvm.compiler.lir.Variable;

import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.ValueUtil;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.Value;

public class SARAVerifyValueComparator implements Comparator<Value> {

    enum ValueType {
        Register,
        Variable,
        JavaConstantValue
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

        throw GraalError.unimplemented("Value compare not implemented for " + value.getClass());
    }

    private static int compareJavaConstant(JavaConstant c1, JavaConstant c2) {
        if (c1.getJavaKind().equals(JavaKind.Int) && c2.getJavaKind().equals(JavaKind.Int)) {
            return c1.asInt() - c2.asInt();
        }

        throw GraalError.unimplemented("JavaConstant compare not implemented for " + c1.getJavaKind() + " and " + c2.getJavaKind());
    }

}
