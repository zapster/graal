/*
 * Copyright (c) 2017, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.reflect.hosted;

// Checkstyle: allow reflection

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import com.oracle.svm.core.annotate.RecomputeFieldValue.CustomFieldValueComputer;
import com.oracle.svm.core.util.VMError;

import jdk.vm.ci.meta.ResolvedJavaField;

public abstract class DeclaredAnnotationsComputer implements CustomFieldValueComputer {

    protected abstract Class<?> getDeclaringClass();

    @Override
    public Object compute(ResolvedJavaField original, ResolvedJavaField annotated, Object receiver) {
        try {
            Method declaredAnnotationsMethod = getDeclaringClass().getDeclaredMethod("declaredAnnotations");
            declaredAnnotationsMethod.setAccessible(true);
            return declaredAnnotationsMethod.invoke(receiver);
        } catch (NoSuchMethodException | SecurityException | IllegalAccessException | IllegalArgumentException | InvocationTargetException ex) {
            throw VMError.shouldNotReachHere(ex);
        }
    }

    public static class FieldDeclaredAnnotationsComputer extends DeclaredAnnotationsComputer {
        @Override
        protected Class<?> getDeclaringClass() {
            return Field.class;
        }
    }

    public static class ExecutableDeclaredAnnotationsComputer extends DeclaredAnnotationsComputer {
        @Override
        protected Class<?> getDeclaringClass() {
            return Executable.class;
        }
    }
}
