#!/bin/bash

# Test script for JVM Renamer Obfuscation
# This script tests the renamer functionality with JVM-only obfuscation (native disabled)

set -e

echo "========================================="
echo "JVM Renamer Obfuscation Test Script"
echo "========================================="

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Check if test input jar is provided
if [ -z "$1" ]; then
    echo -e "${RED}Error: No input JAR file specified${NC}"
    echo "Usage: $0 <input.jar> [output-dir]"
    echo ""
    echo "Example: $0 test-app.jar test-output"
    exit 1
fi

INPUT_JAR="$1"
OUTPUT_DIR="${2:-renamer-test-output}"

# Validate input jar exists
if [ ! -f "$INPUT_JAR" ]; then
    echo -e "${RED}Error: Input JAR file not found: $INPUT_JAR${NC}"
    exit 1
fi

echo -e "${YELLOW}Input JAR: $INPUT_JAR${NC}"
echo -e "${YELLOW}Output directory: $OUTPUT_DIR${NC}"
echo ""

# Build the obfuscator
echo -e "${YELLOW}Building native-obfuscator...${NC}"
./gradlew shadowJar --no-daemon

if [ $? -ne 0 ]; then
    echo -e "${RED}Build failed!${NC}"
    exit 1
fi

OBFUSCATOR_JAR="obfuscator/build/libs/obfuscator-*.jar"

# Find the built jar
if ! ls $OBFUSCATOR_JAR 1> /dev/null 2>&1; then
    echo -e "${RED}Error: Obfuscator JAR not found at $OBFUSCATOR_JAR${NC}"
    exit 1
fi

OBFUSCATOR_JAR=$(ls $OBFUSCATOR_JAR | head -1)
echo -e "${GREEN}Using obfuscator: $OBFUSCATOR_JAR${NC}"
echo ""

# Test 1: Basic renamer with default settings
echo -e "${YELLOW}Test 1: Basic Renamer (classes, methods, fields)${NC}"
java -jar "$OBFUSCATOR_JAR" \
    --enable-java-obfuscation \
    --enable-native-obfuscation=false \
    --enable-renamer \
    --java-string-encryption=false \
    --java-number-obfuscation=false \
    --java-flow-obfuscation=false \
    "$INPUT_JAR" \
    "${OUTPUT_DIR}/test1-basic"

if [ $? -eq 0 ]; then
    echo -e "${GREEN}✓ Test 1 passed${NC}"
else
    echo -e "${RED}✗ Test 1 failed${NC}"
fi
echo ""

# Test 2: Renamer with custom class prefix
echo -e "${YELLOW}Test 2: Renamer with custom class prefix${NC}"
java -jar "$OBFUSCATOR_JAR" \
    --enable-java-obfuscation \
    --enable-native-obfuscation=false \
    --enable-renamer \
    --class-name-prefix="MyApp" \
    --method-name-prefix="m" \
    --field-name-prefix="f" \
    --java-string-encryption=false \
    --java-number-obfuscation=false \
    --java-flow-obfuscation=false \
    "$INPUT_JAR" \
    "${OUTPUT_DIR}/test2-prefix"

if [ $? -eq 0 ]; then
    echo -e "${GREEN}✓ Test 2 passed${NC}"
else
    echo -e "${RED}✗ Test 2 failed${NC}"
fi
echo ""

# Test 3: Renamer with package structure preservation
echo -e "${YELLOW}Test 3: Renamer with package structure preservation${NC}"
java -jar "$OBFUSCATOR_JAR" \
    --enable-java-obfuscation \
    --enable-native-obfuscation=false \
    --enable-renamer \
    --class-keep-package-structure \
    --class-package-prefix="obf" \
    --java-string-encryption=false \
    --java-number-obfuscation=false \
    --java-flow-obfuscation=false \
    "$INPUT_JAR" \
    "${OUTPUT_DIR}/test3-package"

if [ $? -eq 0 ]; then
    echo -e "${GREEN}✓ Test 3 passed${NC}"
else
    echo -e "${RED}✗ Test 3 failed${NC}"
fi
echo ""

# Test 4: Renamer with invokedynamic compatibility
echo -e "${YELLOW}Test 4: Renamer + InvokeDynamic${NC}"
java -jar "$OBFUSCATOR_JAR" \
    --enable-java-obfuscation \
    --enable-native-obfuscation=false \
    --enable-renamer \
    --java-invoke-dynamic \
    --java-string-encryption=false \
    --java-number-obfuscation=false \
    --java-flow-obfuscation=false \
    "$INPUT_JAR" \
    "${OUTPUT_DIR}/test4-invokedynamic"

if [ $? -eq 0 ]; then
    echo -e "${GREEN}✓ Test 4 passed${NC}"
else
    echo -e "${RED}✗ Test 4 failed${NC}"
fi
echo ""

# Test 5: Renamer with control flow
echo -e "${YELLOW}Test 5: Renamer + Control Flow${NC}"
java -jar "$OBFUSCATOR_JAR" \
    --enable-java-obfuscation \
    --enable-native-obfuscation=false \
    --enable-renamer \
    --java-flow-obfuscation \
    --java-string-encryption=false \
    --java-number-obfuscation=false \
    "$INPUT_JAR" \
    "${OUTPUT_DIR}/test5-controlflow"

if [ $? -eq 0 ]; then
    echo -e "${GREEN}✓ Test 5 passed${NC}"
else
    echo -e "${RED}✗ Test 5 failed${NC}"
fi
echo ""

# Test 6: Renamer with all obfuscation features
echo -e "${YELLOW}Test 6: Renamer + All JVM Obfuscation Features${NC}"
java -jar "$OBFUSCATOR_JAR" \
    --enable-java-obfuscation \
    --enable-native-obfuscation=false \
    --enable-renamer \
    --java-string-encryption \
    --java-number-obfuscation \
    --java-flow-obfuscation \
    --java-invoke-dynamic \
    --class-name-prefix="Obf" \
    "$INPUT_JAR" \
    "${OUTPUT_DIR}/test6-full"

if [ $? -eq 0 ]; then
    echo -e "${GREEN}✓ Test 6 passed${NC}"
else
    echo -e "${RED}✗ Test 6 failed${NC}"
fi
echo ""

# Test 7: Only rename methods and fields (keep class names)
echo -e "${YELLOW}Test 7: Rename methods and fields only${NC}"
java -jar "$OBFUSCATOR_JAR" \
    --enable-java-obfuscation \
    --enable-native-obfuscation=false \
    --enable-renamer \
    --rename-classes=false \
    --rename-methods \
    --rename-fields \
    --java-string-encryption=false \
    --java-number-obfuscation=false \
    --java-flow-obfuscation=false \
    "$INPUT_JAR" \
    "${OUTPUT_DIR}/test7-methods-fields-only"

if [ $? -eq 0 ]; then
    echo -e "${GREEN}✓ Test 7 passed${NC}"
else
    echo -e "${RED}✗ Test 7 failed${NC}"
fi
echo ""

echo -e "${GREEN}=========================================${NC}"
echo -e "${GREEN}All tests completed!${NC}"
echo -e "${GREEN}=========================================${NC}"
echo ""
echo "Output directories:"
ls -d "${OUTPUT_DIR}"/test* 2>/dev/null || echo "No output directories found"
echo ""
echo "To run the obfuscated JAR files:"
echo "  java -jar ${OUTPUT_DIR}/test1-basic/<output-jar-name>.jar"
