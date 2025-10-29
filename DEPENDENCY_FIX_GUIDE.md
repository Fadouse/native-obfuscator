# How to Resolve jitpack.io Dependency Issue

If you're experiencing build failures due to jitpack.io connectivity, here are detailed solutions:

## Quick Fix: Build with Network Access

The simplest solution is to build in an environment with unrestricted internet access:

```bash
# On your local machine or a server with internet access
git clone https://github.com/Fadouse/native-obfuscator.git
cd native-obfuscator
git checkout copilot/add-java-jvm-renamer-obfuscation
./gradlew clean build
```

GitHub Actions CI should work automatically as it has network access.

## Alternative: Manual Dependency Installation

If you must build in a restricted environment:

### Step 1: Download Dependencies

On a machine with internet access, download the missing JARs:

```bash
# jphantom
wget https://jitpack.io/com/github/Col-E/jphantom/1.4.3/jphantom-1.4.3.jar

# SSVM (snapshot, may need specific build)
wget https://jitpack.io/io/github/terminalsin/SSVM/1.0.0-SNAPSHOT/SSVM-1.0.0-SNAPSHOT.jar
```

### Step 2: Install to Local Maven Repository

Transfer the JARs to your restricted environment and install:

```bash
mvn install:install-file \
  -Dfile=jphantom-1.4.3.jar \
  -DgroupId=com.github.Col-E \
  -DartifactId=jphantom \
  -Dversion=1.4.3 \
  -Dpackaging=jar

mvn install:install-file \
  -Dfile=SSVM-1.0.0-SNAPSHOT.jar \
  -DgroupId=io.github.terminalsin \
  -DartifactId=SSVM \
  -Dversion=1.0.0-SNAPSHOT \
  -Dpackaging=jar
```

### Step 3: Build with Offline Mode

```bash
./gradlew build --offline
```

## Alternative: Modify build.gradle (Not Recommended)

If you don't need the features provided by these dependencies, you could temporarily comment them out:

```gradle
// In obfuscator/build.gradle, comment out:
// implementation 'com.github.Col-E:jphantom:1.4.3'
// implementation 'io.github.terminalsin:SSVM:1.0.0-SNAPSHOT'
```

**Warning**: This will break Skidfuscator's phantom class generation and SSVM hashing features.

## Verify Dependencies Are Resolved

After installing dependencies, verify:

```bash
./gradlew dependencies --configuration compileClasspath | grep -E "jphantom|SSVM"
```

You should see:
```
+--- com.github.Col-E:jphantom:1.4.3
+--- io.github.terminalsin:SSVM:1.0.0-SNAPSHOT
```

## Test the Renamer

Once built successfully:

```bash
# Build the obfuscator
./gradlew shadowJar

# Run the test suite
./test-renamer.sh test-app-example.jar
```

All 7 test scenarios should pass.

## Common Issues

### Issue: "Could not find jphantom-1.4.3.jar"
**Cause**: Dependency not in local Maven repository  
**Fix**: Download and install manually (see Step 1-2 above)

### Issue: "jitpack.io: No address associated with hostname"
**Cause**: Network cannot resolve jitpack.io DNS  
**Fix**: Use a different network or environment

### Issue: "SSVM version mismatch"
**Cause**: SNAPSHOT versions change frequently  
**Fix**: Download the exact version jitpack built for your commit

## Contact

If you continue to have issues after trying these solutions, please provide:
1. Your environment (OS, Java version, network restrictions)
2. Full error log from `./gradlew build --stacktrace`
3. Output of `./gradlew dependencies --configuration compileClasspath`
