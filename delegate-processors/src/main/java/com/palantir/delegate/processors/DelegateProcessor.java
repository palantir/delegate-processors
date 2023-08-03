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

import com.google.auto.common.MoreElements;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.palantir.delegate.processors.DelegateProcessorStrategy.AdditionalFieldsArguments;
import com.palantir.delegate.processors.DelegateProcessorStrategy.CustomizeArguments;
import com.palantir.delegate.processors.DelegateProcessorStrategy.DelegateMethodArguments;
import com.palantir.delegate.processors.DelegateProcessorStrategy.DelegateTypeArguments;
import com.palantir.goethe.Goethe;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeVariableName;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Generated;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic.Kind;

public abstract class DelegateProcessor extends AbstractProcessor {

    private static final String DELEGATE_NAME = "delegate";

    private final DelegateProcessorStrategy strategy;

    protected DelegateProcessor(DelegateProcessorStrategy strategy) {
        this.strategy = strategy;
    }

    @Override
    public final SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public final Set<String> getSupportedAnnotationTypes() {
        return strategy.supportedAnnotations();
    }

    @Override
    public final boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        ProcessorContext context = ProcessorContext.create(processingEnv);
        roundEnv.getElementsAnnotatedWithAny(annotations.toArray(TypeElement[]::new)).stream()
                .flatMap(element -> annotatedType(context, element))
                .distinct()
                .flatMap(element -> toModelType(element, context).stream())
                .map(modelType -> generateJavaFile(modelType, context))
                .forEach(javaFile -> {
                    if (javaFile.typeSpec.originatingElements.size() != 1) {
                        context.messager()
                                .printMessage(
                                        Kind.ERROR,
                                        "Expected '" + javaFile.typeSpec.name
                                                + "' to have a single originating element.");
                    }
                    try {
                        Goethe.formatAndEmit(javaFile, context.filer());
                    } catch (RuntimeException e) {
                        context.messager()
                                .printMessage(
                                        Kind.ERROR,
                                        "Failed to write class '" + javaFile.typeSpec.name + "': "
                                                + Throwables.getStackTraceAsString(e),
                                        Iterables.getFirst(javaFile.typeSpec.originatingElements, null));
                    }
                });

        return false;
    }

    private Optional<AnnotatedType> toModelType(TypeElement typeElement, ProcessorContext context) {
        List<TypeMirror> interfaces = getInterfaces(typeElement, context);
        if (interfaces.isEmpty()) {
            context.messager()
                    .printMessage(
                            Kind.ERROR,
                            "No interfaces found on annotated type: " + typeElement.getSimpleName(),
                            typeElement);
            return Optional.empty();
        }
        return Optional.of(AnnotatedType.builder()
                .type(typeElement)
                .addAllInterfaces(interfaces)
                .addAllMethods(toModelMethods(typeElement, context))
                .build());
    }

    private List<AnnotatedTypeMethod> toModelMethods(TypeElement typeElement, ProcessorContext context) {
        return MoreElements.getLocalAndInheritedMethods(typeElement, context.types(), context.elements()).stream()
                .filter(executableElement -> !Methods.isObjectMethod(context.elements(), executableElement))
                .map(element -> AnnotatedTypeMethod.builder()
                        .implementation(element)
                        .addAllOverridden(Methods.getInterfaceMethods(element, context))
                        .build())
                // Filter out anything that isn't provided by an interface.
                .filter(method -> !method.overridden().isEmpty())
                .collect(Collectors.toUnmodifiableList());
    }

    private JavaFile generateJavaFile(AnnotatedType annotatedType, ProcessorContext context) {
        return JavaFile.builder(
                        context.elements()
                                .getPackageOf(annotatedType.type())
                                .getQualifiedName()
                                .toString(),
                        generateTypeSpec(annotatedType, context))
                .skipJavaLangImports(true)
                .indent("    ")
                .build();
    }

    private TypeSpec generateTypeSpec(AnnotatedType annotatedType, ProcessorContext context) {
        TypeElement typeElement = annotatedType.type();
        String generatedTypeSimpleName =
                strategy.generatedTypeName(typeElement.getSimpleName().toString());
        ClassName generatedClassName = ClassName.get(
                context.elements()
                        .getPackageOf(annotatedType.type())
                        .getQualifiedName()
                        .toString(),
                generatedTypeSimpleName);
        TypeSpec.Builder builder = TypeSpec.classBuilder(generatedTypeSimpleName)
                .addOriginatingElement(typeElement)
                .addAnnotation(AnnotationSpec.builder(Generated.class)
                        .addMember("value", "$S", getClass().getName())
                        .build())
                .addModifiers(Modifier.FINAL)
                .addTypeVariables(Lists.transform(typeElement.getTypeParameters(), TypeVariableName::get));
        if (typeElement.getModifiers().contains(Modifier.PUBLIC)) {
            builder.addModifiers(Modifier.PUBLIC);
        }
        annotatedType.interfaces().forEach(builder::addSuperinterface);

        final TypeName delegateTypeName = strategy.delegateType(DelegateTypeArguments.builder()
                .context(context)
                .type(annotatedType)
                .build());
        if (delegateTypeName instanceof TypeVariableName) {
            builder.addTypeVariable((TypeVariableName) delegateTypeName);
        }

        FieldSpec delegateField = FieldSpec.builder(delegateTypeName, DELEGATE_NAME)
                .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                .build();
        ImmutableList<FieldSpec> allFields = ImmutableList.<FieldSpec>builder()
                .add(delegateField)
                .addAll(strategy.additionalFields(AdditionalFieldsArguments.builder()
                        .context(context)
                        .type(annotatedType)
                        .build()))
                .build();
        builder.addFields(allFields);

        List<FieldSpec> ctorFields = allFields.stream()
                .filter(field -> field.initializer.isEmpty() && !field.modifiers.contains(Modifier.STATIC))
                .collect(Collectors.toUnmodifiableList());

        builder.addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PRIVATE)
                .addParameters(ctorFields.stream()
                        .map(fieldSpec -> ParameterSpec.builder(fieldSpec.type, fieldSpec.name)
                                .build())
                        .collect(ImmutableList.toImmutableList()))
                .addCode(ctorFields.stream()
                        .map(field -> CodeBlock.builder()
                                .addStatement(
                                        "this.$1N = $2T.requireNonNull($1N, $3S)",
                                        field.name,
                                        Objects.class,
                                        field.name)
                                .build())
                        .collect(CodeBlock.joining("")))
                .build());

        for (AnnotatedTypeMethod method : annotatedType.methods()) {
            builder.addMethod(generateMethodSpec(DelegateMethodArguments.builder()
                    .context(context)
                    .type(annotatedType)
                    .method(method)
                    .delegate(delegateField)
                    .build()));
        }
        builder.addMethod(MethodSpec.methodBuilder("toString")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .returns(String.class)
                .addStatement("return $S + this.$N + $S", generatedTypeSimpleName + '{', DELEGATE_NAME, "}")
                .build());

        TypeName generatedTypeName = builder.typeVariables.isEmpty()
                ? generatedClassName
                : ParameterizedTypeName.get(
                        generatedClassName,
                        builder.typeVariables.stream()
                                .map(param -> (TypeName) TypeVariableName.get(param.name))
                                .toArray(TypeName[]::new));
        strategy.customize(
                CustomizeArguments.builder()
                        .context(context)
                        .type(annotatedType)
                        .generatedTypeName(generatedTypeName)
                        .delegateTypeName(delegateTypeName)
                        .build(),
                builder);
        return builder.build();
    }

    private MethodSpec generateMethodSpec(DelegateMethodArguments arguments) {
        LocalVariable throwable = LocalVariable.builder()
                .type(ClassName.get(Throwable.class))
                .name("_throwable")
                .build();

        MethodSpec.Builder method = Methods.createMethod(arguments);

        strategy.before(arguments).ifPresent(method::addCode);

        Optional<CodeBlock> onFailure = strategy.onFailure(arguments, throwable);
        Optional<CodeBlock> alwaysAfter = strategy.alwaysAfter(arguments);
        boolean requiresTry = onFailure.isPresent() || alwaysAfter.isPresent();
        if (requiresTry) {
            method.beginControlFlow("try");
        }

        if (Methods.isVoid(arguments.method(), arguments.context())) {
            strategy.onSuccess(arguments, Optional.empty())
                    .ifPresentOrElse(
                            onSuccess -> {
                                method.addStatement(Methods.delegateInvocation(arguments))
                                        .addCode(onSuccess);
                            },
                            () -> {
                                method.addStatement(Methods.delegateInvocation(arguments));
                            });
        } else {
            LocalVariable result = LocalVariable.builder()
                    .type(method.build().returnType)
                    .name("_result")
                    .build();
            strategy.onSuccess(arguments, Optional.of(result))
                    .ifPresentOrElse(
                            onSuccess -> {
                                method.addStatement(
                                                "$T $N = $L",
                                                result.type(),
                                                result.name(),
                                                Methods.delegateInvocation(arguments))
                                        .addCode(onSuccess)
                                        .addStatement("return $N", result.name());
                            },
                            () -> {
                                method.addStatement("return $L", Methods.delegateInvocation(arguments));
                            });
        }

        onFailure.ifPresent(onFailureBlock -> {
            method.nextControlFlow("catch ($T $N)", throwable.type(), throwable.name())
                    .addCode(onFailureBlock)
                    .addStatement("throw $N", throwable.name());
        });

        alwaysAfter.ifPresent(alwaysAfterBlock -> {
            method.nextControlFlow("finally").addCode(alwaysAfterBlock);
        });

        if (requiresTry) {
            method.endControlFlow();
        }

        return method.build();
    }

    private static List<TypeMirror> getInterfaces(TypeElement typeElement, ProcessorContext context) {
        if (typeElement.asType().getKind() == TypeKind.ERROR) {
            context.messager()
                    .printMessage(
                            Kind.ERROR, "Type could not be resolved: " + typeElement.getSimpleName(), typeElement);
            return List.of();
        }
        if (typeElement.getKind() == ElementKind.INTERFACE) {
            return List.of(typeElement.asType());
        }
        ImmutableList.Builder<TypeMirror> builder = ImmutableList.builder();
        for (TypeMirror interfaceMirror : typeElement.getInterfaces()) {
            Element interfaceElement = context.types().asElement(interfaceMirror);
            if (!(interfaceElement instanceof TypeElement)) {
                context.messager()
                        .printMessage(
                                Kind.ERROR,
                                "Expected the interface element to be a TypeElement. TypeMirror: " + interfaceMirror
                                        + ", Element: " + interfaceElement,
                                typeElement);
            } else if (interfaceElement.asType().getKind() == TypeKind.ERROR) {
                // In this case another annotation processor may need to generate the interface before we can move
                // forward in a later round. For now we can fail the build, and defer implementation until later on.
                context.messager()
                        .printMessage(Kind.ERROR, "Interface could not be resolved: " + interfaceMirror, typeElement);
            } else {
                builder.add(interfaceMirror);
            }
        }
        return builder.build();
    }

    @SuppressWarnings("checkstyle:CyclomaticComplexity")
    private Stream<TypeElement> annotatedType(ProcessorContext context, Element annotatedElement) {
        ElementKind elementKind = annotatedElement.getKind();
        switch (elementKind) {
            case PACKAGE:
            case ENUM:
            case ENUM_CONSTANT:
            case ANNOTATION_TYPE:
            case LOCAL_VARIABLE:
            case EXCEPTION_PARAMETER:
            case STATIC_INIT:
            case INSTANCE_INIT:
            case TYPE_PARAMETER:
            case OTHER:
            case RESOURCE_VARIABLE:
            case MODULE:
            case FIELD:
            case PARAMETER:
                // FIELD and PARAMETER could potentially be supported, but it's not clear why we would.
                // Holding off until we have a specific use case in mind.
                context.messager()
                        .printMessage(
                                Kind.ERROR, "Unsupported annotated element kind: " + elementKind, annotatedElement);
                return Stream.empty();
            case CLASS:
            case INTERFACE:
                return Stream.of((TypeElement) annotatedElement);
            case METHOD:
            case CONSTRUCTOR:
                return annotatedType(context, annotatedElement.getEnclosingElement());
        }
        throw new IllegalArgumentException("Unknown ElementKind: " + elementKind);
    }
}
