/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.driver;

import java.lang.reflect.Field;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Queue;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.graalvm.compiler.options.OptionDescriptor;
import org.graalvm.nativeimage.Feature;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.svm.core.annotate.AutomaticFeature;
import com.oracle.svm.core.option.APIOption;
import com.oracle.svm.core.option.APIOption.APIOptionKind;
import com.oracle.svm.core.option.SubstrateOptionsParser;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.hosted.FeatureImpl;
import com.oracle.svm.hosted.option.HostedOptionParser;

class APIOptionHandler extends NativeImage.OptionHandler<NativeImage> {

    static final class OptionInfo {
        final String builderOption;
        final String defaultValue;
        final String helpText;
        final boolean hasPathArguments;

        OptionInfo(String builderOption, String defaultValue, String helpText, boolean hasPathArguments) {
            this.builderOption = builderOption;
            this.defaultValue = defaultValue;
            this.helpText = helpText;
            this.hasPathArguments = hasPathArguments;
        }
    }

    private final SortedMap<String, OptionInfo> apiOptions;

    APIOptionHandler(NativeImage nativeImage) {
        super(nativeImage);
        apiOptions = ImageSingletons.lookup(APIOptionCollector.class).options;
    }

    @Override
    boolean consume(Queue<String> args) {
        String headArg = args.peek();
        String[] optionParts = headArg.split("=", 2);
        OptionInfo option = apiOptions.get(optionParts[0]);
        if (option != null) {
            args.poll();
            String builderOption = option.builderOption;
            String optionValue = option.defaultValue;
            if (optionParts.length == 2) {
                optionValue = optionParts[1];
            }
            if (optionValue != null) {
                if (option.hasPathArguments) {
                    optionValue = Arrays.stream(optionValue.split(","))
                                    .filter(s -> !s.isEmpty())
                                    .map(s -> nativeImage.canonicalize(Paths.get(s)).toString())
                                    .collect(Collectors.joining(","));
                }
                builderOption += optionValue;
            }
            nativeImage.addImageBuilderArg(builderOption);
            return true;
        }
        return false;
    }

    void printOptions(Consumer<String> println) {
        apiOptions.forEach((optionName, optionInfo) -> {
            SubstrateOptionsParser.printOption(println, optionName, optionInfo.helpText, 4, 22, 66);
        });
    }
}

@AutomaticFeature
final class APIOptionCollector implements Feature {

    final SortedMap<String, APIOptionHandler.OptionInfo> options = new TreeMap<>();

    @Platforms(Platform.HOSTED_ONLY.class)
    APIOptionCollector() {
    }

    @Override
    public void duringSetup(DuringSetupAccess access) {
        FeatureImpl.DuringSetupAccessImpl accessImpl = (FeatureImpl.DuringSetupAccessImpl) access;
        SortedMap<String, OptionDescriptor> hostedOptions = new TreeMap<>();
        SortedMap<String, OptionDescriptor> runtimeOptions = new TreeMap<>();
        HostedOptionParser.collectOptions(accessImpl.getImageClassLoader(), hostedOptions, runtimeOptions);
        hostedOptions.values().forEach(o -> extractOption(NativeImage.oH, o));
        runtimeOptions.values().forEach(o -> extractOption(NativeImage.oR, o));
    }

    private void extractOption(String optionPrefix, OptionDescriptor optionDescriptor) {
        try {
            Field optionField = optionDescriptor.getDeclaringClass().getDeclaredField(optionDescriptor.getFieldName());
            APIOption apiAnnotation = optionField.getAnnotation(APIOption.class);
            if (apiAnnotation != null) {
                String builderOption = optionPrefix;
                if (optionDescriptor.getOptionValueType().equals(Boolean.class)) {
                    VMError.guarantee(!apiAnnotation.kind().equals(APIOptionKind.Paths));
                    VMError.guarantee(apiAnnotation.defaultValue().length == 0);
                    builderOption += apiAnnotation.kind().equals(APIOptionKind.Negated) ? "-" : "+";
                    builderOption += optionDescriptor.getName();
                } else {
                    VMError.guarantee(!apiAnnotation.kind().equals(APIOptionKind.Negated));
                    VMError.guarantee(apiAnnotation.defaultValue().length <= 1, "SustrateVM APIOption cannot have more than one default value");
                    builderOption += optionDescriptor.getName();
                    builderOption += "=";
                }
                String defaultValue = apiAnnotation.defaultValue().length == 0 ? null : apiAnnotation.defaultValue()[0];
                String helpText = optionDescriptor.getHelp();
                VMError.guarantee(helpText != null && !helpText.isEmpty(), "SustrateVM APIOption needs to provide help text");
                helpText = helpText.substring(0, 1).toLowerCase() + helpText.substring(1);
                options.put("--" + apiAnnotation.name(), new APIOptionHandler.OptionInfo(builderOption, defaultValue, helpText, apiAnnotation.kind().equals(APIOptionKind.Paths)));
            }
        } catch (NoSuchFieldException e) {
            /* Does not qualify as APIOption */
        }
    }
}
