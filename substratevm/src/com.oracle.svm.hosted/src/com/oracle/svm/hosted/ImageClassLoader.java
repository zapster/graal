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
import java.net.URL;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.graalvm.collections.EconomicSet;
import org.graalvm.compiler.options.Option;
import org.graalvm.compiler.word.Word;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.svm.core.option.HostedOptionKey;
import com.oracle.svm.core.util.InterruptImageBuilding;
import com.oracle.svm.core.util.VMError;

public final class ImageClassLoader {

    /* { GR-8964: Add an option to control tracing. */
    static class Options {
        @Option(help = "Verbose tracing of image class loading for GR-8964.")//
        public static final HostedOptionKey<Boolean> GR8964Tracing = new HostedOptionKey<>(false);
    }
    /* } GR-8964: Add an option to control tracing. */

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

    /** A public factory method that accepts a gr8964Tracing parameter. */
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

    private static Path toRealPath(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            throw VMError.shouldNotReachHere("Path.toRealPath failed for " + p, e);
        }
    }

    private void initAllClasses() {
        final ForkJoinPool executor = new ForkJoinPool(Runtime.getRuntime().availableProcessors());

        Set<Path> uniquePaths = new TreeSet<>(Comparator.comparing(t -> toRealPath(t)));
        final boolean debugGR8964 = Boolean.valueOf(System.getProperty("debug_gr_8964", "false"));
        if (debugGR8964) {
            System.err.print("[ImageClassLoader.initAllClasses");
            List<Path> pathList = new ArrayList<>();
            for (String classPathEntry : classpath) {
                System.err.println();
                System.err.println("  [classPathEntry: " + classPathEntry);
                final Path path = Paths.get(classPathEntry);
                pathList.add(path);
                final Path absolutePath;
                System.err.println("             path: " + path.toString());
                if (!path.isAbsolute()) {
                    absolutePath = path.toAbsolutePath();
                    System.err.println("     absolutePath: " + path.toString());
                } else {
                    absolutePath = path;
                }
                System.err.print("                 ");
                System.err.print(path.isAbsolute() ? "  absolute" : "");
                final boolean exists = Files.exists(absolutePath);
                System.err.print(exists ? "  exists" : "");
                if (exists) {
                    System.err.print(Files.isDirectory(absolutePath) ? "  directory" : "");
                    System.err.print(Files.isRegularFile(absolutePath, LinkOption.NOFOLLOW_LINKS) ? "  file" : "");
                    System.err.print(Files.isSymbolicLink(absolutePath) ? "  symlink" : "");
                    System.err.print(Files.isReadable(absolutePath) ? "  readable" : "");
                    try {
                        System.err.print("  " + Files.getLastModifiedTime(absolutePath).toString());
                    } catch (IOException ioe) {
                        System.err.print("  n/a");
                    }
                }
                System.err.print("]");
            }
            System.err.println("]");
            uniquePaths.addAll(pathList);
        } else {
            uniquePaths.addAll(
                            Arrays.stream(classpath)
                                            .map(Paths::get)
                                            .collect(Collectors.toList()));
        }
        uniquePaths.parallelStream().forEach(path -> loadClassesFromPath(executor, path));

        executor.awaitQuiescence(CLASS_LOADING_TIMEOUT_IN_MINUTES, TimeUnit.MINUTES);
    }

    private void loadClassesFromPath(ForkJoinPool executor, Path path) {
        if (Files.exists(path)) {
            if (path.getFileName().toString().endsWith(".jar")) {
                try {
                    try (FileSystem jarFileSystem = FileSystems.newFileSystem(URI.create("jar:file:" + path), Collections.emptyMap())) {
                        initAllClasses(jarFileSystem.getPath("/"), executor);
                    }
                } catch (ClosedByInterruptException ignored) {
                    throw new InterruptImageBuilding();
                } catch (IOException e) {
                    throw shouldNotReachHere(e);
                }
            } else {
                initAllClasses(path, executor);
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

    public URL findResourceByName(String resource) {
        return classLoader.getResource(resource);
    }

    public InputStream findResourceAsStreamByName(String resource) {
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
