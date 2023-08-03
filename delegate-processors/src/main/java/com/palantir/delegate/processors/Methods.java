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
import com.google.auto.common.MoreTypes;
import com.palantir.delegate.processors.DelegateProcessorStrategy.DelegateMethodArguments;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeVariableName;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.util.Elements;

final class Methods {

    static boolean isVoid(AnnotatedTypeMethod method, ProcessorContext context) {
        return context.types()
                .isSameType(
                        context.types().getNoType(TypeKind.VOID),
                        method.implementation().getReturnType());
    }

    // TODO(ckozak): Must relax types from concrete implementations to match the union of implemented interfaces.
    //   For example `Object foo() throws IOException` may be overridden `UUID foo() throws FileNotFoundException`.
    static MethodSpec.Builder createMethod(DelegateMethodArguments arguments) {
        ExecutableElement implementationMethod = arguments.method().implementation();
        ExecutableElement method = findContractMethod(arguments.method(), arguments.context());
        DeclaredType declaredType = arguments
                .context()
                .types()
                .getDeclaredType(
                        arguments.type().type(),
                        arguments.type().type().getTypeParameters().stream()
                                .map(TypeParameterElement::asType)
                                .toArray(TypeMirror[]::new));
        ExecutableType executableType =
                (ExecutableType) arguments.context().types().asMemberOf(declaredType, method);
        List<? extends TypeMirror> resolvedParameterTypes = executableType.getParameterTypes();
        List<? extends TypeMirror> resolvedThrownTypes = executableType.getThrownTypes();
        TypeMirror resolvedReturnType = executableType.getReturnType();
        String methodName = method.getSimpleName().toString();
        MethodSpec.Builder builder = MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class);

        if (arguments.method().overridden().stream()
                .anyMatch(interfaceMethod -> MoreElements.isAnnotationPresent(interfaceMethod, Deprecated.class))) {
            builder.addAnnotation(Deprecated.class)
                    .addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
                            .addMember("value", "$S", "deprecation")
                            .build());
        }

        for (TypeParameterElement typeParameterElement : method.getTypeParameters()) {
            TypeVariable var = (TypeVariable) typeParameterElement.asType();
            builder.addTypeVariable(TypeVariableName.get(var));
        }
        builder.varargs(method.isVarArgs());

        builder.returns(TypeName.get(resolvedReturnType));
        List<? extends VariableElement> methodParameters = method.getParameters();
        for (int i = 0, size = methodParameters.size(); i < size; i++) {
            builder.addParameter(ParameterSpec.builder(
                            TypeName.get(resolvedParameterTypes.get(i)),
                            // Always use parameter names from the implementation since it's the annotated element.
                            // Some IDE annotation processor implementations fail to handle superclass/superinterface
                            // argument names which makes running annotation processor tests more challenging.
                            implementationMethod
                                    .getParameters()
                                    .get(i)
                                    .getSimpleName()
                                    .toString())
                    .build());
        }
        resolvedThrownTypes.forEach(resolvedThrownType -> builder.addException(TypeName.get(resolvedThrownType)));
        return builder;
    }

    static CodeBlock delegateInvocation(DelegateMethodArguments arguments) {
        ExecutableElement contractMethod = findContractMethod(arguments.method(), arguments.context());
        return CodeBlock.of(
                "this.$N.$L($L)",
                arguments.delegate(),
                contractMethod.getSimpleName(),
                arguments.method().implementation().getParameters().stream()
                        .map(param -> CodeBlock.of("$N", param.getSimpleName()))
                        .collect(CodeBlock.joining(",")));
    }

    // Returns the most specific interface method if possible. If the method signature is provided by
    // multiple interfaces, the implementation element is returned instead.
    private static ExecutableElement findContractMethod(
            AnnotatedTypeMethod annotatedTypeMethod, ProcessorContext context) {
        List<ExecutableElement> overridden = annotatedTypeMethod.overridden();
        if (overridden.size() == 1) {
            return overridden.get(0);
        }
        for (ExecutableElement element : overridden) {
            ExecutableType executableType = (ExecutableType) element.asType();
            boolean allMatch = true;
            for (ExecutableElement potential : overridden) {
                if (potential == element) {
                    continue;
                }
                if (!context.types().isSubsignature(executableType, (ExecutableType) potential.asType())) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch) {
                return element;
            }
        }
        return annotatedTypeMethod.implementation();
    }

    static boolean isObjectMethod(Elements elements, ExecutableElement methodElement) {
        TypeElement object = elements.getTypeElement(Object.class.getName());
        for (Element element : object.getEnclosedElements()) {
            if (element instanceof ExecutableElement) {
                ExecutableElement executableElement = (ExecutableElement) element;
                if (elements.overrides(methodElement, executableElement, object)) {
                    return true;
                }
            }
        }
        return false;
    }

    static Set<ExecutableElement> getInterfaceMethods(ExecutableElement methodElement, ProcessorContext context) {
        Set<ExecutableElement> methods = new LinkedHashSet<>();
        collectInterfaceMethods(methodElement, context, methods::add);
        return methods;
    }

    private static void collectInterfaceMethods(
            ExecutableElement methodElement, ProcessorContext context, Predicate<ExecutableElement> results) {
        TypeElement enclosing = (TypeElement) methodElement.getEnclosingElement();
        if (enclosing.getKind() == ElementKind.INTERFACE) {
            if (!results.test(methodElement)) {
                // Already done
                return;
            }
        }
        for (TypeMirror interfaceMirror : enclosing.getInterfaces()) {
            collectInterfaceMethods(interfaceMirror, methodElement, context, results);
        }
    }

    private static void collectInterfaceMethods(
            TypeMirror interfaceMirror,
            ExecutableElement methodElement,
            ProcessorContext context,
            Predicate<ExecutableElement> results) {
        TypeElement interfaceElement = MoreTypes.asTypeElement(interfaceMirror);
        for (Element element : interfaceElement.getEnclosedElements()) {
            if (element.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement interfaceMethod = (ExecutableElement) element;
            if (MoreElements.overrides(
                    methodElement,
                    interfaceMethod,
                    (TypeElement) methodElement.getEnclosingElement(),
                    context.types())) {
                collectInterfaceMethods(interfaceMethod, context, results);
            }
        }
        for (TypeMirror superInterfaceMirror : interfaceElement.getInterfaces()) {
            collectInterfaceMethods(superInterfaceMirror, methodElement, context, results);
        }
    }

    private Methods() {}
}
