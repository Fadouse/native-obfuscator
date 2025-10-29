# JVM Renamer Obfuscation 使用文档

## 概述

JVM Renamer 混淆器能够对 Java 类、方法和字段进行重命名混淆，提供详细的配置选项，支持与 InvokeDynamic、控制流混淆以及反射加载的兼容性。

## 功能特性

### 1. 全面的重命名能力
- **类重命名**: 重命名所有用户类（包括混淆器注入的类）
- **方法重命名**: 重命名所有方法（保留特殊方法如 `<init>`, `<clinit>`）
- **字段重命名**: 重命名所有字段

### 2. 详细的自定义配置
- 为类、方法、字段分别设置名称前缀
- 自定义字符集（例如：仅使用小写字母、数字等）
- 控制包结构（保持或扁平化）
- 设置包前缀

### 3. 兼容性保证
- **InvokeDynamic 兼容**: 与动态方法调用完全兼容
- **控制流兼容**: 与控制流扁平化混淆协同工作
- **反射兼容**: 支持反射加载的类

## 命令行选项

### 基础选项

```bash
--enable-renamer                    # 启用 JVM 重命名混淆（默认：禁用）
--rename-classes                    # 重命名类（默认：启用当重命名器启用时）
--rename-methods                    # 重命名方法（默认：启用当重命名器启用时）
--rename-fields                     # 重命名字段（默认：启用当重命名器启用时）
```

### 类重命名配置

```bash
--class-name-prefix=<prefix>        # 类名前缀（默认：空）
--class-name-charset=<charset>      # 类名字符集（默认：a-zA-Z）
--class-keep-package-structure      # 保持原包结构（默认：false）
--class-package-prefix=<prefix>     # 包前缀（默认：空）
```

### 方法重命名配置

```bash
--method-name-prefix=<prefix>       # 方法名前缀（默认：空）
--method-name-charset=<charset>     # 方法名字符集（默认：a-zA-Z）
```

### 字段重命名配置

```bash
--field-name-prefix=<prefix>        # 字段名前缀（默认：空）
--field-name-charset=<charset>      # 字段名字符集（默认：a-zA-Z）
```

## 使用示例

### 示例 1: 基础重命名（仅 JVM 混淆）

```bash
java -jar native-obfuscator.jar \
    --enable-java-obfuscation \
    --enable-native-obfuscation=false \
    --enable-renamer \
    input.jar output-dir
```

### 示例 2: 自定义前缀

```bash
java -jar native-obfuscator.jar \
    --enable-java-obfuscation \
    --enable-native-obfuscation=false \
    --enable-renamer \
    --class-name-prefix="MyApp" \
    --method-name-prefix="m" \
    --field-name-prefix="f" \
    input.jar output-dir
```

### 示例 3: 保持包结构

```bash
java -jar native-obfuscator.jar \
    --enable-java-obfuscation \
    --enable-native-obfuscation=false \
    --enable-renamer \
    --class-keep-package-structure \
    --class-package-prefix="obf" \
    input.jar output-dir
```

### 示例 4: 自定义字符集

```bash
java -jar native-obfuscator.jar \
    --enable-java-obfuscation \
    --enable-native-obfuscation=false \
    --enable-renamer \
    --class-name-charset="abc" \
    --method-name-charset="xyz" \
    --field-name-charset="123" \
    input.jar output-dir
```

### 示例 5: 只重命名方法和字段

```bash
java -jar native-obfuscator.jar \
    --enable-java-obfuscation \
    --enable-native-obfuscation=false \
    --enable-renamer \
    --rename-classes=false \
    --rename-methods \
    --rename-fields \
    input.jar output-dir
```

### 示例 6: 与其他混淆技术组合

#### 重命名 + InvokeDynamic

```bash
java -jar native-obfuscator.jar \
    --enable-java-obfuscation \
    --enable-native-obfuscation=false \
    --enable-renamer \
    --java-invoke-dynamic \
    input.jar output-dir
```

#### 重命名 + 控制流混淆

```bash
java -jar native-obfuscator.jar \
    --enable-java-obfuscation \
    --enable-native-obfuscation=false \
    --enable-renamer \
    --java-flow-obfuscation \
    input.jar output-dir
```

#### 重命名 + 所有 JVM 混淆

```bash
java -jar native-obfuscator.jar \
    --enable-java-obfuscation \
    --enable-native-obfuscation=false \
    --enable-renamer \
    --java-string-encryption \
    --java-number-obfuscation \
    --java-flow-obfuscation \
    --java-invoke-dynamic \
    --class-name-prefix="Obf" \
    input.jar output-dir
```

### 示例 7: JVM + Native 混淆

```bash
java -jar native-obfuscator.jar \
    --enable-java-obfuscation \
    --enable-native-obfuscation \
    --enable-renamer \
    --enable-virtualization \
    --flatten-control-flow \
    input.jar output-dir
```

## 配置详解

### RenamerConfig 类

RenamerConfig 提供了完整的重命名配置：

```java
RenamerConfig config = new RenamerConfig.Builder()
    .setEnabled(true)
    .setRenameClasses(true)
    .setRenameMethods(true)
    .setRenameFields(true)
    .setClassPrefix("Obf")
    .setClassCharset("abcdefghijklmnopqrstuvwxyz")
    .setClassKeepPackageStructure(false)
    .setClassPackagePrefix("obf")
    .setMethodPrefix("m")
    .setMethodCharset("abcdefghijklmnopqrstuvwxyz")
    .setFieldPrefix("f")
    .setFieldCharset("abcdefghijklmnopqrstuvwxyz")
    .setReflectionCompatible(true)
    .setInvokeDynamicCompatible(true)
    .build();
```

### 配置选项说明

| 选项 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `enabled` | boolean | false | 是否启用重命名 |
| `renameClasses` | boolean | true | 是否重命名类 |
| `renameMethods` | boolean | true | 是否重命名方法 |
| `renameFields` | boolean | true | 是否重命名字段 |
| `classPrefix` | String | "" | 类名前缀 |
| `classCharset` | String | "a-zA-Z" | 类名字符集 |
| `classKeepPackageStructure` | boolean | false | 是否保持包结构 |
| `classPackagePrefix` | String | "" | 包前缀 |
| `methodPrefix` | String | "" | 方法名前缀 |
| `methodCharset` | String | "a-zA-Z" | 方法名字符集 |
| `fieldPrefix` | String | "" | 字段名前缀 |
| `fieldCharset` | String | "a-zA-Z" | 字段名字符集 |
| `reflectionCompatible` | boolean | true | 反射兼容 |
| `invokeDynamicCompatible` | boolean | true | InvokeDynamic兼容 |

## 兼容性说明

### 1. InvokeDynamic 兼容性

重命名器已经过优化，能够正确处理 `invokedynamic` 指令：
- 自动更新方法引用
- 保持动态方法调用的正确性
- 与 `--java-invoke-dynamic` 选项完全兼容

### 2. 控制流兼容性

重命名器与控制流混淆完全兼容：
- 可以同时使用 `--enable-renamer` 和 `--java-flow-obfuscation`
- 重命名不会破坏控制流结构
- 两者组合提供更强的保护

### 3. 反射加载兼容性

重命名器支持反射加载的类：
- 正确处理 `Class.forName()` 调用
- 维护类名映射
- 自动适配 `reflectionCompatible` 模式

### 4. Native 混淆兼容性

重命名器可以与 Native 混淆协同工作：
- 先进行 JVM 层重命名
- 再进行 Native 转换
- 所有重命名的类都会被正确处理

## 测试

使用提供的测试脚本进行测试：

```bash
./test-renamer.sh your-app.jar
```

测试脚本会运行以下测试场景：
1. 基础重命名（类、方法、字段）
2. 自定义前缀
3. 包结构保持
4. 重命名 + InvokeDynamic
5. 重命名 + 控制流
6. 重命名 + 所有 JVM 混淆
7. 只重命名方法和字段

## 注意事项

### 保留的名称
以下名称不会被重命名：
- 特殊方法：`<init>`, `<clinit>`
- Main 方法：`public static void main(String[])`
- JDK 类和方法
- 库类（通过 `-l` 指定的依赖库）

### 黑白名单
可以使用黑白名单来控制哪些类/方法应该被重命名：
```bash
java -jar native-obfuscator.jar \
    --enable-java-obfuscation \
    --enable-renamer \
    -jw whitelist.txt \
    -jb blacklist.txt \
    input.jar output-dir
```

黑白名单格式：
```
# 类
com/example/MyClass

# 方法
com/example/MyClass#myMethod#()V

# 通配符
com/example/**
```

### 性能影响
重命名混淆对运行时性能影响极小：
- 仅改变符号名称
- 不改变字节码逻辑
- 不影响 JVM 优化

## 故障排除

### 问题 1: 运行时 ClassNotFoundException

**原因**: 可能是反射调用使用了硬编码的类名

**解决方案**:
1. 使用 `--class-keep-package-structure` 保持包结构
2. 在黑名单中排除相关类
3. 确保 `reflectionCompatible` 为 true

### 问题 2: NoSuchMethodError

**原因**: 方法重命名可能影响了动态调用

**解决方案**:
1. 检查是否使用了 `--java-invoke-dynamic`
2. 确保 `invokeDynamicCompatible` 为 true
3. 在黑名单中排除相关方法

### 问题 3: 与第三方库不兼容

**原因**: 第三方库可能使用了反射或其他动态机制

**解决方案**:
1. 使用 `-l` 选项指定依赖库目录
2. 在黑名单中排除库类
3. 只重命名应用代码，不重命名库代码

## 最佳实践

1. **渐进式启用**: 先测试基础重命名，再逐步添加其他混淆选项
2. **使用前缀**: 为重命名的符号添加前缀，便于调试
3. **保持包结构**: 对于大型项目，建议保持包结构以降低风险
4. **测试覆盖**: 确保所有功能在重命名后仍能正常工作
5. **版本控制**: 保存重命名映射，便于后续调试和更新

## 技术细节

### 重命名算法
- 使用字母序列生成器创建唯一名称
- 避免与 JDK 保留字冲突
- 维护层次结构的一致性

### 映射维护
- 自动更新所有引用
- 处理方法重写和接口实现
- 维护类型签名的正确性

### 兼容性处理
- 检测并保留特殊方法
- 处理泛型和注解
- 维护序列化兼容性（在可能的情况下）

## 示例项目

查看 `obfuscator/test_data/tests/` 目录下的测试用例，了解重命名器在不同场景下的表现。
