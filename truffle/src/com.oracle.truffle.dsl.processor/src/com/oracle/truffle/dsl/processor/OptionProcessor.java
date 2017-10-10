/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.dsl.processor;

import static javax.lang.model.element.Modifier.ABSTRACT;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.FilerException;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic.Kind;

import org.graalvm.options.OptionCategory;
import org.graalvm.options.OptionDescriptor;
import org.graalvm.options.OptionDescriptors;
import org.graalvm.options.OptionKey;

import com.oracle.truffle.api.Option;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.instrumentation.TruffleInstrument;
import com.oracle.truffle.dsl.processor.java.ElementUtils;
import com.oracle.truffle.dsl.processor.java.model.CodeExecutableElement;
import com.oracle.truffle.dsl.processor.java.model.CodeTree;
import com.oracle.truffle.dsl.processor.java.model.CodeTreeBuilder;
import com.oracle.truffle.dsl.processor.java.model.CodeTypeElement;
import com.oracle.truffle.dsl.processor.java.transform.FixWarningsVisitor;
import com.oracle.truffle.dsl.processor.java.transform.GenerateOverrideVisitor;

/**
 * Processes static fields annotated with {@link Option}. An {@link OptionDescriptors}
 * implementation is generated for each top level class containing at least one such field. The name
 * of the generated class for top level class {@code com.foo.Bar} is
 * {@code com.foo.Bar_OptionDescriptors}.
 */
@SupportedAnnotationTypes({"com.oracle.truffle.api.Option", "com.oracle.truffle.api.Option.Group"})
public class OptionProcessor extends AbstractProcessor {

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latest();
    }

    private final Set<Element> processed = new HashSet<>();

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            return true;
        }
        ProcessorContext context = new ProcessorContext(processingEnv, null);
        ProcessorContext.setThreadLocalInstance(context);
        try {
            Map<Element, OptionsInfo> map = new HashMap<>();
            for (Element element : roundEnv.getElementsAnnotatedWith(Option.class)) {
                if (!processed.contains(element)) {
                    processed.add(element);
                    Element topElement = element.getEnclosingElement();

                    OptionsInfo options = map.get(topElement);
                    if (options == null) {
                        options = new OptionsInfo(topElement);
                        map.put(topElement, options);
                    }
                    AnnotationMirror mirror = ElementUtils.findAnnotationMirror(processingEnv, element.getAnnotationMirrors(), Option.class);
                    try {
                        processElement(element, mirror, options);
                    } catch (Throwable t) {
                        handleThrowable(t, topElement);
                    }
                }
            }

            Map<String, OptionInfo> seenKeys = new HashMap<>();
            for (OptionsInfo infos : map.values()) {
                for (OptionInfo info : infos.options) {
                    if (seenKeys.containsKey(info.name)) {
                        OptionInfo otherInfo = seenKeys.get(info.name);
                        String message = "Two options with duplicated resolved descriptor name '%s' found.";
                        info.valid = false;
                        otherInfo.valid = false;
                        error(info.field, info.annotation, message, info.name);
                        error(otherInfo.field, otherInfo.annotation, message, otherInfo.name);
                    } else {
                        seenKeys.put(info.name, info);
                    }
                }
            }

            for (OptionsInfo infos : map.values()) {
                ListIterator<OptionInfo> listIterator = infos.options.listIterator();
                while (listIterator.hasNext()) {
                    OptionInfo info = listIterator.next();
                    if (info.valid) {
                        ExpectError.assertNoErrorExpected(processingEnv, info.field);
                    } else {
                        listIterator.remove();
                    }
                }
                Collections.sort(infos.options, new Comparator<OptionInfo>() {
                    public int compare(OptionInfo o1, OptionInfo o2) {
                        return o1.name.compareTo(o2.name);
                    }
                });
            }

            for (OptionsInfo info : map.values()) {
                try {
                    generateOptionDescriptor(info);
                } catch (Throwable t) {
                    handleThrowable(t, info.type);
                }
            }
        } finally {
            ProcessorContext.setThreadLocalInstance(null);
        }

        return true;
    }

    private boolean processElement(Element element, AnnotationMirror elementAnnotation, OptionsInfo info) {
        ProcessorContext context = ProcessorContext.getInstance();

        if (!element.getModifiers().contains(Modifier.STATIC)) {
            error(element, elementAnnotation, "Option field must be static");
            return false;
        }
        if (element.getModifiers().contains(Modifier.PRIVATE)) {
            error(element, elementAnnotation, "Option field cannot be private");
            return false;
        }

        String[] groupPrefixStrings = null;
        Option.Group prefix = info.type.getAnnotation(Option.Group.class);

        if (prefix != null) {
            groupPrefixStrings = prefix.value();
        } else {
            TypeMirror erasedTruffleType = context.getEnvironment().getTypeUtils().erasure(context.getType(TruffleLanguage.class));
            if (context.getEnvironment().getTypeUtils().isAssignable(info.type.asType(), erasedTruffleType)) {
                TruffleLanguage.Registration registration = info.type.getAnnotation(TruffleLanguage.Registration.class);
                if (registration != null) {
                    groupPrefixStrings = new String[]{registration.id()};
                    if (groupPrefixStrings[0].isEmpty()) {
                        error(element, elementAnnotation, "%s must specify an id such that Truffle options can infer their prefix.", TruffleLanguage.Registration.class.getSimpleName());
                        return false;
                    }
                }

            } else if (context.getEnvironment().getTypeUtils().isAssignable(info.type.asType(), context.getType(TruffleInstrument.class))) {
                TruffleInstrument.Registration registration = info.type.getAnnotation(TruffleInstrument.Registration.class);
                if (registration != null) {
                    groupPrefixStrings = new String[]{registration.id()};
                    if (groupPrefixStrings[0].isEmpty()) {
                        error(element, elementAnnotation, "%s must specify an id such that Truffle options can infer their prefix.", TruffleInstrument.Registration.class.getSimpleName());
                        return false;
                    }
                }
            }
        }

        if (groupPrefixStrings == null || groupPrefixStrings.length == 0) {
            groupPrefixStrings = new String[]{""};
        }

        Option annotation = element.getAnnotation(Option.class);
        assert annotation != null;
        assert element instanceof VariableElement;
        assert element.getKind() == ElementKind.FIELD;
        VariableElement field = (VariableElement) element;
        String fieldName = field.getSimpleName().toString();

        Elements elements = processingEnv.getElementUtils();
        Types types = processingEnv.getTypeUtils();

        TypeMirror fieldType = field.asType();
        if (fieldType.getKind() != TypeKind.DECLARED) {
            error(element, elementAnnotation, "Option field must be of type " + OptionKey.class.getName());
            return false;
        }
        TypeMirror optionKeyType = elements.getTypeElement(OptionKey.class.getName()).asType();
        if (!types.isSubtype(fieldType, types.erasure(optionKeyType))) {
            error(element, elementAnnotation, "Option field type %s is not a subclass of %s", fieldType, optionKeyType);
            return false;
        }

        if (!field.getModifiers().contains(Modifier.STATIC)) {
            error(element, elementAnnotation, "Option field must be static");
            return false;
        }
        if (field.getModifiers().contains(Modifier.PRIVATE)) {
            error(element, elementAnnotation, "Option field cannot be private");
            return false;
        }

        String help = annotation.help();
        if (help.length() != 0) {
            char firstChar = help.charAt(0);
            if (!Character.isUpperCase(firstChar)) {
                error(element, elementAnnotation, "Option help text must start with upper case letter");
                return false;
            }
        }

        AnnotationValue value = ElementUtils.getAnnotationValue(elementAnnotation, "name", false);
        String optionName;
        if (value == null) {
            optionName = fieldName;
        } else {
            optionName = annotation.name();
        }

        if (!optionName.isEmpty() && !Character.isUpperCase(optionName.charAt(0))) {
            error(element, elementAnnotation, "Option names must start with capital letter");
            return false;
        }

        boolean deprecated = annotation.deprecated();

        OptionCategory category = annotation.category();

        if (category == null) {
            category = OptionCategory.DEBUG;
        }

        for (String group : groupPrefixStrings) {
            String name;
            if (group.isEmpty() && optionName.isEmpty()) {
                error(element, elementAnnotation, "Both group and option name cannot be empty");
                continue;
            } else if (optionName.isEmpty()) {
                name = group;
            } else {
                if (group.isEmpty()) {
                    name = optionName;
                } else {
                    name = group + "." + optionName;
                }
            }
            info.options.add(new OptionInfo(name, help, field, elementAnnotation, deprecated, category));
        }
        return true;
    }

    private static void error(Element element, AnnotationMirror annotation, String message, Object... args) {
        ProcessingEnvironment processingEnv = ProcessorContext.getInstance().getEnvironment();
        String formattedMessage = String.format(message, args);
        if (ExpectError.isExpectedError(processingEnv, element, formattedMessage)) {
            return;
        }
        processingEnv.getMessager().printMessage(Kind.ERROR, formattedMessage, element, annotation);
    }

    private void generateOptionDescriptor(OptionsInfo info) {
        Element element = info.type;
        ProcessorContext context = ProcessorContext.getInstance();

        CodeTypeElement unit = generateDescriptors(context, element, info);
        DeclaredType overrideType = (DeclaredType) context.getType(Override.class);
        DeclaredType unusedType = (DeclaredType) context.getType(SuppressWarnings.class);
        unit.accept(new GenerateOverrideVisitor(overrideType), null);
        unit.accept(new FixWarningsVisitor(context.getEnvironment(), unusedType, overrideType), null);
        try {
            unit.accept(new CodeWriter(context.getEnvironment(), element), null);
        } catch (RuntimeException e) {
            if (e.getCause() instanceof FilerException) {
                // ignore spurious errors of source file already created in Eclipse.
                if (e.getCause().getMessage().startsWith("Source file already created")) {
                    return;
                }
            }
        }

    }

    private void handleThrowable(Throwable t, Element e) {
        String message = "Uncaught error in " + getClass().getSimpleName() + " while processing " + e + " ";
        ProcessorContext.getInstance().getEnvironment().getMessager().printMessage(Kind.ERROR, message + ": " + ElementUtils.printException(t), e);
    }

    private CodeTypeElement generateDescriptors(ProcessorContext context, Element element, OptionsInfo model) {
        String optionsClassName = ElementUtils.getSimpleName(element.asType()) + OptionDescriptors.class.getSimpleName();
        TypeElement sourceType = (TypeElement) model.type;
        PackageElement pack = context.getEnvironment().getElementUtils().getPackageOf(sourceType);
        Set<Modifier> typeModifiers = ElementUtils.modifiers(Modifier.FINAL);
        CodeTypeElement descriptors = new CodeTypeElement(typeModifiers, ElementKind.CLASS, pack, optionsClassName);
        DeclaredType optionDescriptorsType = context.getDeclaredType(OptionDescriptors.class);
        descriptors.getImplements().add(optionDescriptorsType);

        ExecutableElement get = ElementUtils.findExecutableElement(optionDescriptorsType, "get");
        CodeExecutableElement getMethod = CodeExecutableElement.clone(processingEnv, get);
        getMethod.getModifiers().remove(ABSTRACT);
        CodeTreeBuilder builder = getMethod.createBuilder();

        String nameVariableName = getMethod.getParameters().get(0).getSimpleName().toString();
        builder.startSwitch().string(nameVariableName).end().startBlock();
        for (OptionInfo info : model.options) {
            builder.startCase().doubleQuote(info.name).end().startCaseBlock();
            builder.startReturn().tree(createBuildOptionDescriptor(context, info)).end();
            builder.end(); // case
        }
        builder.end(); // block
        builder.returnNull();

        descriptors.add(getMethod);

        CodeExecutableElement iteratorMethod = CodeExecutableElement.clone(processingEnv, ElementUtils.findExecutableElement(optionDescriptorsType, "iterator"));
        iteratorMethod.getModifiers().remove(ABSTRACT);
        builder = iteratorMethod.createBuilder();

        builder.startReturn();
        if (model.options.isEmpty()) {
            builder.startStaticCall(context.getType(Collections.class), "<OptionDescriptor> emptyList().iterator").end();
        } else {
            builder.startStaticCall(context.getType(Arrays.class), "asList");
            for (OptionInfo info : model.options) {
                builder.startGroup();
                builder.startIndention();
                builder.newLine();
                builder.tree(createBuildOptionDescriptor(context, info));
                builder.end();
                builder.end();
            }
            builder.end(); /// asList call
            builder.newLine();
            builder.startCall("", "iterator").end();
        }
        builder.end(); // return
        descriptors.add(iteratorMethod);

        return descriptors;
    }

    private static CodeTree createBuildOptionDescriptor(ProcessorContext context, OptionInfo info) {
        CodeTreeBuilder builder = CodeTreeBuilder.createBuilder();
        builder.startStaticCall(context.getType(OptionDescriptor.class), "newBuilder");
        VariableElement var = info.field;
        builder.staticReference(var.getEnclosingElement().asType(), var.getSimpleName().toString());
        builder.doubleQuote(info.name);
        builder.end(); // newBuilder call
        if (info.deprecated) {
            builder.startCall("", "deprecated").string("true").end();
        } else {
            builder.startCall("", "deprecated").string("false").end();
        }
        builder.startCall("", "help").doubleQuote(info.help).end();
        builder.startCall("", "category").staticReference(context.getType(OptionCategory.class), info.category.name()).end();
        builder.startCall("", "build").end();
        return builder.build();
    }

    static class OptionInfo implements Comparable<OptionInfo> {

        boolean valid = true;
        final String name;
        final String help;
        final boolean deprecated;
        final VariableElement field;
        final AnnotationMirror annotation;
        final OptionCategory category;

        OptionInfo(String name, String help, VariableElement field, AnnotationMirror annotation, boolean deprecated, OptionCategory category) {
            this.name = name;
            this.help = help;
            this.field = field;
            this.annotation = annotation;
            this.deprecated = deprecated;
            this.category = category;
        }

        @Override
        public int compareTo(OptionInfo other) {
            return name.compareTo(other.name);
        }

    }

    static class OptionsInfo {

        final Element type;
        final List<OptionInfo> options = new ArrayList<>();

        OptionsInfo(Element topDeclaringType) {
            this.type = topDeclaringType;
        }
    }
}
