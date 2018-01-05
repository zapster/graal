/*
 * Copyright (c) 2012, 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.hosted;

import static com.oracle.svm.core.util.VMError.shouldNotReachHere;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.graalvm.compiler.word.Word;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.util.EconomicSet;

import com.oracle.svm.core.util.InterruptImageBuilding;

public final class ImageClassLoader {

    private static final int CLASS_LENGTH = ".class".length();
    private static final int CLASS_LOADING_TIMEOUT_IN_MINUTES = 10;

    static {
        /*
         * ImageClassLoader is one of the first classes used during image generation, so early
         * enough to ensure that we can use the Word type.
         */
        Word.ensureInitialized();
    }

    private final Platform platform;
    private final ClassLoader classLoader;
    private final String[] classpath;
    private final EconomicSet<Class<?>> systemClasses = EconomicSet.create();
    private final EconomicSet<Method> systemMethods = EconomicSet.create();
    private final EconomicSet<Field> systemFields = EconomicSet.create();

    private ImageClassLoader(Platform platform, String[] classpath, ClassLoader classLoader) {
        this.platform = platform;
        this.classpath = classpath;
        this.classLoader = classLoader;
    }

    public static ImageClassLoader create(Platform platform, String[] classpathAll, ClassLoader classLoader) {
        ArrayList<String> classpathFiltered = new ArrayList<>(classpathAll.length);
        classpathFiltered.addAll(Arrays.asList(classpathAll));

        /* The Graal SDK is on the boot class path, and it contains annotated types. */
        for (String s : System.getProperty("sun.boot.class.path").split(File.pathSeparator)) {
            if (s.contains("graal-sdk")) {
                classpathFiltered.add(s);
            }
        }

        ImageClassLoader result = new ImageClassLoader(platform, classpathFiltered.toArray(new String[classpathFiltered.size()]), classLoader);
        result.initAllClasses();
        return result;
    }

    private void initAllClasses() {
        final ForkJoinPool executor = new ForkJoinPool(Runtime.getRuntime().availableProcessors());

        Set<String> uniquePaths = new HashSet<>(Arrays.asList(classpath));
        uniquePaths.parallelStream().forEach(path -> loadClassesFromPath(executor, path));

        executor.awaitQuiescence(CLASS_LOADING_TIMEOUT_IN_MINUTES, TimeUnit.MINUTES);
    }

    private void loadClassesFromPath(ForkJoinPool executor, String path) {
        File file = new File(path);
        if (file.exists()) {
            if (path.endsWith(".jar")) {
                try {
                    try (FileSystem jarFileSystem = FileSystems.newFileSystem(URI.create("jar:file:" + file.getAbsolutePath()), Collections.emptyMap())) {
                        initAllClasses(jarFileSystem.getPath("/"), executor);
                    }
                } catch (ClosedByInterruptException ignored) {
                    throw new InterruptImageBuilding();
                } catch (IOException e) {
                    throw shouldNotReachHere(e);
                }
            } else {
                initAllClasses(FileSystems.getDefault().getPath(path), executor);
            }
        }
    }

    private void findSystemElements(Class<?> systemClass) {
        try {
            for (Method systemMethod : systemClass.getDeclaredMethods()) {
                if (NativeImageGenerator.includedIn(platform, systemMethod.getAnnotation(Platforms.class))) {
                    synchronized (systemMethods) {
                        systemMethods.add(systemMethod);
                    }
                }
            }
        } catch (Throwable t) {
            handleClassLoadingError(t);
        }
        try {
            for (Field systemField : systemClass.getDeclaredFields()) {
                if (NativeImageGenerator.includedIn(platform, systemField.getAnnotation(Platforms.class))) {
                    synchronized (systemFields) {
                        systemFields.add(systemField);
                    }
                }
            }
        } catch (Throwable t) {
            handleClassLoadingError(t);
        }
    }

    @SuppressWarnings("unused")
    private void handleClassLoadingError(Throwable t) {
        /* we ignore class loading errors due to incomplete paths that people often have */
    }

    private void initAllClasses(final Path root, ForkJoinPool executor) {
        FileVisitor<Path> visitor = new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                executor.execute(() -> {
                    String fileName = root.relativize(file).toString().replace('/', '.');
                    if (fileName.endsWith(".class")) {
                        String className = fileName.substring(0, fileName.length() - CLASS_LENGTH);
                        try {
                            Class<?> systemClass = Class.forName(className, false, classLoader);
                            if (includedInPlatform(systemClass)) {
                                synchronized (systemClasses) {
                                    systemClasses.add(systemClass);
                                }
                                findSystemElements(systemClass);
                            }
                        } catch (Throwable t) {
                            handleClassLoadingError(t);
                        }
                    }
                });
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                /* Silently ignore inaccessible files or directories. */
                return FileVisitResult.CONTINUE;
            }
        };

        try {
            Files.walkFileTree(root, visitor);
        } catch (IOException ex) {
            throw shouldNotReachHere(ex);
        }
    }

    private boolean includedInPlatform(Class<?> clazz) {
        Class<?> cur = clazz;
        do {
            if (!NativeImageGenerator.includedIn(platform, cur.getAnnotation(Platforms.class))) {
                return false;
            }
            cur = cur.getEnclosingClass();
        } while (cur != null);
        return true;
    }

    public InputStream findResourceByName(String resource) {
        return classLoader.getResourceAsStream(resource);
    }

    public Class<?> findClassByName(String name) {
        return findClassByName(name, true);
    }

    public Class<?> findClassByName(String name, boolean failIfClassMissing) {
        try {
            if (name.indexOf('.') == -1) {
                switch (name) {
                    case "boolean":
                        return boolean.class;
                    case "char":
                        return char.class;
                    case "float":
                        return float.class;
                    case "double":
                        return double.class;
                    case "byte":
                        return byte.class;
                    case "short":
                        return short.class;
                    case "int":
                        return int.class;
                    case "long":
                        return long.class;
                    case "void":
                        return void.class;
                }
            }

            return Class.forName(name, false, classLoader);
        } catch (ClassNotFoundException ex) {
            if (failIfClassMissing) {
                throw shouldNotReachHere("class " + name + " not found");
            }
        }
        return null;
    }

    public List<String> getClasspath() {
        return Collections.unmodifiableList(Arrays.asList(classpath));
    }

    public <T> List<Class<? extends T>> findSubclasses(Class<T> baseClass) {
        ArrayList<Class<? extends T>> result = new ArrayList<>();
        for (Class<?> systemClass : systemClasses) {
            if (baseClass.isAssignableFrom(systemClass)) {
                result.add(systemClass.asSubclass(baseClass));
            }
        }
        return result;
    }

    public List<Class<?>> findAnnotatedClasses(Class<? extends Annotation> annotationClass) {
        ArrayList<Class<?>> result = new ArrayList<>();
        for (Class<?> systemClass : systemClasses) {
            if (systemClass.getAnnotation(annotationClass) != null) {
                result.add(systemClass);
            }
        }
        return result;
    }

    public List<Method> findAnnotatedMethods(Class<? extends Annotation> annotationClass) {
        ArrayList<Method> result = new ArrayList<>();
        for (Method method : systemMethods) {
            if (method.getAnnotation(annotationClass) != null) {
                result.add(method);
            }
        }
        return result;
    }

    public List<Method> findAnnotatedMethods(Class<? extends Annotation>[] annotationClasses) {
        ArrayList<Method> result = new ArrayList<>();
        for (Method method : systemMethods) {
            boolean match = true;
            for (Class<? extends Annotation> annotationClass : annotationClasses) {
                if (method.getAnnotation(annotationClass) == null) {
                    match = false;
                    break;
                }
            }
            if (match) {
                result.add(method);
            }
        }
        return result;
    }

    List<Field> findAnnotatedFields(Class<? extends Annotation> annotationClass) {
        ArrayList<Field> result = new ArrayList<>();
        for (Field field : systemFields) {
            if (field.getAnnotation(annotationClass) != null) {
                result.add(field);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public List<Class<? extends Annotation>> allAnnotations() {
        return StreamSupport.stream(systemClasses.spliterator(), false)
                        .filter(Class::isAnnotation)
                        .map(clazz -> (Class<? extends Annotation>) clazz)
                        .collect(Collectors.toList());
    }

    /**
     * Returns all annotations on classes, methods, and fields (enabled or disabled based on the
     * parameters) of the given annotation class.
     */
    <T extends Annotation> List<T> findAnnotations(Class<T> annotationClass) {
        List<T> result = new ArrayList<>();
        for (Class<?> clazz : findAnnotatedClasses(annotationClass)) {
            result.add(clazz.getAnnotation(annotationClass));
        }
        for (Method method : findAnnotatedMethods(annotationClass)) {
            result.add(method.getAnnotation(annotationClass));
        }
        for (Field field : findAnnotatedFields(annotationClass)) {
            result.add(field.getAnnotation(annotationClass));
        }
        return result;
    }

    public ClassLoader getClassLoader() {
        return classLoader;
    }

    public static boolean isHostedClass(Class<?> clazz) {
        return clazz.getName().contains("hosted") || clazz.getName().contains("hotspot");
    }
}
