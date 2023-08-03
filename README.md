Hello, World!
<p align="right">
<a href="https://autorelease.general.dmz.palantir.tech/palantir/delegate-processors"><img src="https://img.shields.io/badge/Perform%20an-Autorelease-success.svg" alt="Autorelease"></a>
</p>

Delegate Processors
============
A library to simplify implementation of annotation processors which delegate to annotated components. This processor allows delegation based on implemented interfaces rather than the annotation concrete class itself. This allows arbitrarily many wrappers based on annotations found on the implementation class, where requiring the concrete implementation would allow at most one wrapper.

Concretely, this allows us to generate wrappers for a type along the lines of:

```java
public final class MyResource implements MyService, Closeable {

    @Override
    public void ping() {}

    @Override
    public void close() throws IOException {}
}
```

And generate a wrapper taking the form:

```java
public final class MyResourceWrapper<T extends MyService & Closeable>
    implements MyService, Closeable {

    private final T delegate;

    public static <T extends MyService & Closeable> MyResourceWrapper<T> of(T input) {
        return new MyResourceWrapper<>(input);
    }

    MyResourceWrapper(T delegate) {
        this.delegate = delegate;
    }

    @Override
    public void ping() {
        this.delegate.ping();
    }
    // etc...
}
```

Usage
----

Extend the `DelegateProcessor`, providing a `DelegateProcessorStrategy` to the super constructor.

```java
public final class PrintingProcessor extends DelegateProcessor {

    public PrintingProcessor() {
        super(PrintingProcessorStrategy.INSTANCE);
    }
}
```

```java
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
```

Add a `META-INF/gradle/incremental.annotation.processors` resource to allow gradle incremental builds contents.
This processor delegates to another type, so it's safe to use the `ISOLATING` option.

```text
com.palantir.my.ProcessorFqcn,ISOLATING
```

Add a service-loader file either by writing the file yourself, or using the `AutoService` annotation processor.

Gradle Tasks
------------
`./gradlew tasks` - to get the list of gradle tasks


Start Developing
----------------
Run one of the following commands:

* `./gradlew idea` for IntelliJ
* `./gradlew eclipse` for Eclipse
