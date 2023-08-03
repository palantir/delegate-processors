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

package com.palantir.delegate.processors.processor;

import com.palantir.delegate.processors.DelegateProcessorStrategy;
import com.palantir.delegate.processors.LocalVariable;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.TypeSpec;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.element.Modifier;

public enum PrintingProcessorStrategy implements DelegateProcessorStrategy {
    INSTANCE;

    @Override
    public Set<String> supportedAnnotations() {
        return Set.of(Delegate.class.getName());
    }

    @Override
    public String generatedTypeName(String annotatedTypeName) {
        return "Printing" + annotatedTypeName;
    }

    @Override
    public Optional<CodeBlock> before(DelegateMethodArguments arguments) {
        return Optional.of(CodeBlock.builder()
                .addStatement(
                        "System.out.println($S)",
                        arguments.method().implementation().getSimpleName().toString())
                .build());
    }

    @Override
    public Optional<CodeBlock> onSuccess(DelegateMethodArguments _arguments, Optional<LocalVariable> result) {
        CodeBlock.Builder builder = CodeBlock.builder();
        result.ifPresentOrElse(
                variable -> {
                    builder.addStatement("System.out.println($N)", variable.name());
                },
                () -> {
                    builder.addStatement("System.out.println(\"void\")");
                });
        return Optional.of(builder.build());
    }

    @Override
    @SuppressWarnings("RegexpSinglelineJava")
    public Optional<CodeBlock> onFailure(DelegateMethodArguments _arguments, LocalVariable throwable) {
        return Optional.of(CodeBlock.builder()
                .addStatement("$N.printStackTrace()", throwable.name())
                .build());
    }

    @Override
    public Optional<CodeBlock> alwaysAfter(DelegateMethodArguments _arguments) {
        return Optional.of(CodeBlock.builder()
                .addStatement("System.out.println($S)", "done")
                .build());
    }

    @Override
    public void customize(CustomizeArguments arguments, TypeSpec.Builder generatedType) {
        generatedType.addMethod(MethodSpec.methodBuilder("of")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addTypeVariables(generatedType.typeVariables)
                .addParameters(generatedType.fieldSpecs.stream()
                        .map(spec -> ParameterSpec.builder(spec.type, spec.name).build())
                        .collect(Collectors.toList()))
                .returns(arguments.generatedTypeName())
                .addStatement(
                        "return new $T($L)",
                        arguments.generatedTypeName(),
                        generatedType.fieldSpecs.stream()
                                .map(spec -> CodeBlock.of("$N", spec.name))
                                .collect(CodeBlock.joining(", ")))
                .build());
    }
}
