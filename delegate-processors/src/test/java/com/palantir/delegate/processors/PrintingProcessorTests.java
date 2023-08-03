/*
 * (c) Copyright 2021 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.delegate.processors;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.io.ByteSource;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import com.palantir.delegate.processors.example.BoundedCallableGreetingResource;
import com.palantir.delegate.processors.example.CallableGreetingResource;
import com.palantir.delegate.processors.example.CloseableGreetingResource;
import com.palantir.delegate.processors.example.CollisionService;
import com.palantir.delegate.processors.example.ExpandedGreetingResource;
import com.palantir.delegate.processors.example.GreetingResource;
import com.palantir.delegate.processors.example.NoInterfacesResource;
import com.palantir.delegate.processors.processor.PrintingProcessor;
import com.palantir.delegate.processors.processor.PrintingProcessorStrategy;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

public class PrintingProcessorTests {

    private static final boolean DEV_MODE = Boolean.getBoolean("recreate");
    private static final Path TEST_CLASSES_BASE_DIR = Paths.get("src", "test", "java");
    private static final Path RESOURCES_BASE_DIR = Paths.get("src", "test", "resources");

    @Test
    public void testExampleFileCompiles() {
        assertTestFileCompileAndMatches(TEST_CLASSES_BASE_DIR, GreetingResource.class);
    }

    @Test
    public void testMultipleInterfacesSimple() {
        assertTestFileCompileAndMatches(TEST_CLASSES_BASE_DIR, CloseableGreetingResource.class);
    }

    @Test
    public void testMultipleInterfacesParameterized() {
        assertTestFileCompileAndMatches(TEST_CLASSES_BASE_DIR, CallableGreetingResource.class);
    }

    @Test
    public void testMultipleInterfacesParameterizedWithBounds() {
        assertTestFileCompileAndMatches(TEST_CLASSES_BASE_DIR, BoundedCallableGreetingResource.class);
    }

    @Test
    public void testNameCollision() {
        assertTestFileCompileAndMatches(TEST_CLASSES_BASE_DIR, CollisionService.class);
    }

    @Test
    public void testMultipleInterfacesCollapsed() {
        // This case is a bit subtle:
        // the generated wrapper must implement the same interfaces in the same order as the input,
        // however it's unnecessary to retain both for the delegate reference.
        assertTestFileCompileAndMatches(TEST_CLASSES_BASE_DIR, ExpandedGreetingResource.class);
    }

    @Test
    public void testNoInterfaces() {
        Compilation compilation = compileTestClass(TEST_CLASSES_BASE_DIR, NoInterfacesResource.class);
        CompilationSubject.assertThat(compilation)
                .hadErrorContaining("No interfaces found on annotated type: NoInterfacesResource");
    }

    private static void assertTestFileCompileAndMatches(Path basePath, Class<?> clazz) {
        Compilation compilation = compileTestClass(basePath, clazz);
        CompilationSubject.assertThat(compilation).succeededWithoutWarnings();
        String generatedClassName = PrintingProcessorStrategy.INSTANCE.generatedTypeName(clazz.getSimpleName());
        String generatedFqnClassName = clazz.getPackage().getName() + "." + generatedClassName;
        String generatedClassFileRelativePath = generatedFqnClassName.replaceAll("\\.", "/") + ".java";
        Assertions.assertThat(compilation.generatedFile(StandardLocation.SOURCE_OUTPUT, generatedClassFileRelativePath))
                .hasValueSatisfying(
                        javaFileObject -> assertContentsMatch(javaFileObject, generatedClassFileRelativePath));
    }

    private static Compilation compileTestClass(Path basePath, Class<?> clazz) {
        Path clazzPath = basePath.resolve(Paths.get(
                Joiner.on("/").join(Splitter.on(".").split(clazz.getPackage().getName())),
                clazz.getSimpleName() + ".java"));
        try {
            return Compiler.javac()
                    .withOptions("-source", "11", "-Werror", "-Xlint:deprecation", "-Xlint:unchecked")
                    .withProcessors(new PrintingProcessor())
                    .compile(JavaFileObjects.forResource(clazzPath.toUri().toURL()));
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    private static void assertContentsMatch(JavaFileObject javaFileObject, String generatedClassFile) {
        try {
            Path output = RESOURCES_BASE_DIR.resolve(generatedClassFile + ".generated");
            String generatedContents = readJavaFileObject(javaFileObject);
            if (DEV_MODE) {
                Files.deleteIfExists(output);
                output.toFile().getParentFile().mkdirs();
                Files.write(output, generatedContents.getBytes(StandardCharsets.UTF_8));
            }
            Assertions.assertThat(generatedContents).isEqualTo(readFromFile(output));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String readJavaFileObject(JavaFileObject javaFileObject) throws IOException {
        return new ByteSource() {
            @Override
            public InputStream openStream() throws IOException {
                return javaFileObject.openInputStream();
            }
        }.asCharSource(StandardCharsets.UTF_8).read();
    }

    private static String readFromFile(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
