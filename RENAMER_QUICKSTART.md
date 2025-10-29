# JVM Renamer Quick Start Guide

## Quick Start - 5 Minutes

### 1. Build the obfuscator

```bash
./gradlew shadowJar
```

### 2. Test with the example application

```bash
# Test the original JAR
java -jar test-app-example.jar

# Apply basic renaming (JVM only, no native)
java -jar obfuscator/build/libs/obfuscator-*.jar \
    --enable-java-obfuscation \
    --enable-native-obfuscation=false \
    --enable-renamer \
    --java-string-encryption=false \
    --java-number-obfuscation=false \
    --java-flow-obfuscation=false \
    test-app-example.jar \
    renamer-output

# Test the obfuscated JAR
java -jar renamer-output/test-app-example.jar
```

### 3. Inspect the results

```bash
# Extract and decompile to see renamed classes
unzip -l renamer-output/test-app-example.jar
```

## Common Use Cases

### Case 1: Basic Renaming (Recommended for first-time users)

```bash
java -jar obfuscator.jar \
    --enable-java-obfuscation \
    --enable-native-obfuscation=false \
    --enable-renamer \
    input.jar output-dir
```

### Case 2: Renaming with Custom Prefixes

```bash
java -jar obfuscator.jar \
    --enable-java-obfuscation \
    --enable-native-obfuscation=false \
    --enable-renamer \
    --class-name-prefix="MyApp" \
    --method-name-prefix="m" \
    --field-name-prefix="f" \
    input.jar output-dir
```

### Case 3: Maximum JVM Protection

```bash
java -jar obfuscator.jar \
    --enable-java-obfuscation \
    --enable-native-obfuscation=false \
    --enable-renamer \
    --java-string-encryption \
    --java-number-obfuscation \
    --java-flow-obfuscation \
    --java-invoke-dynamic \
    input.jar output-dir
```

### Case 4: JVM + Native Protection

```bash
java -jar obfuscator.jar \
    --enable-java-obfuscation \
    --enable-native-obfuscation \
    --enable-renamer \
    --enable-virtualization \
    --flatten-control-flow \
    input.jar output-dir
```

## Running the Test Suite

Use the provided test script:

```bash
./test-renamer.sh test-app-example.jar
```

This will run 7 different test scenarios and verify the renamer works correctly.

## Command-Line Reference

### Essential Options

| Option | Description |
|--------|-------------|
| `--enable-renamer` | Enable JVM renamer obfuscation |
| `--enable-java-obfuscation` | Enable Java-layer obfuscation (required for renamer) |
| `--enable-native-obfuscation=false` | Disable native conversion (for JVM-only testing) |

### Renaming Control

| Option | Description |
|--------|-------------|
| `--rename-classes` | Rename classes (default: true when enabled) |
| `--rename-methods` | Rename methods (default: true when enabled) |
| `--rename-fields` | Rename fields (default: true when enabled) |

### Customization

| Option | Example | Description |
|--------|---------|-------------|
| `--class-name-prefix` | `--class-name-prefix="Obf"` | Prefix for class names |
| `--method-name-prefix` | `--method-name-prefix="m"` | Prefix for method names |
| `--field-name-prefix` | `--field-name-prefix="f"` | Prefix for field names |
| `--class-name-charset` | `--class-name-charset="abc"` | Characters for class names |
| `--class-keep-package-structure` | Enable to preserve packages | Keep package structure |
| `--class-package-prefix` | `--class-package-prefix="obf"` | Package prefix |

## Troubleshooting

### Issue: "Could not resolve dependencies"

**Solution**: The project requires internet access to download dependencies. If offline:
```bash
# Use gradle offline mode (requires pre-cached dependencies)
./gradlew shadowJar --offline
```

### Issue: Obfuscated JAR doesn't run

**Checklist**:
1. ✓ Did you enable `--enable-java-obfuscation`?
2. ✓ Is the main class correctly preserved?
3. ✓ Are required libraries in classpath?
4. ✓ Did you test with `--enable-native-obfuscation=false` first?

### Issue: ClassNotFoundException at runtime

**Solution**: Add classes to whitelist or use:
```bash
--class-keep-package-structure
```

### Issue: NoSuchMethodError

**Solution**: Ensure invokedynamic compatibility:
```bash
--java-invoke-dynamic
```

## Next Steps

1. Read the full documentation: [RENAMER_USAGE.md](RENAMER_USAGE.md)
2. Explore test cases in `obfuscator/test_data/tests/`
3. Join the discussion on GitHub Issues

## Support

For issues and questions:
- GitHub Issues: https://github.com/Fadouse/native-obfuscator/issues
- Check existing tests for examples
- Read the main README.md for general usage

## Version

This feature is available in version 3.5.4r and later.
