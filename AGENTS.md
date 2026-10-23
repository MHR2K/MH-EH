# AGENTS.md - EhViewer Android Project

This document provides guidelines for AI agents working on the EhViewer Android application.

## Project Overview

EhViewer is an E-Hentai Android browser application written in Kotlin and Java. The project uses:
- **Build System**: Gradle with Android Gradle Plugin 8.13.2
- **Language**: Kotlin 2.1.0 and Java 21
- **Min SDK**: 23 | **Target SDK**: 29 | **Compile SDK**: 35
- **Package**: `com.hippo.ehviewer`

## Build Commands

### Debug Build
```bash
./gradlew app:assembleDebug
```
APK output: `app/build/outputs/apk/debug/`

### Release Build
```bash
./gradlew app:assembleRelease
```

### Clean Build
```bash
./gradlew clean
```

### Run Unit Tests
```bash
./gradlew app:test             # Run all tests
./gradlew test                 # Alternative
```

### Run Single Test Class
```bash
./gradlew test --tests "com.hippo.util.PathNaturalComparatorTest"
./gradlew app:test --tests "com.hippo.ehviewer.client.parser.GalleryListParserTest"
```

### Run Single Test Method
```bash
./gradlew test --tests "com.hippo.util.NaturalComparatorTest.testOrder"
```

### Run Lint
```bash
./gradlew lint                 # Run lint checks
./gradlew lint --info         # Verbose output
```

### Lint Configuration
Located in `app/build.gradle`:
```gradle
lint {
    disable 'MissingTranslation'
    abortOnError true
    checkReleaseBuilds true
}
```

### Generate License Report
```bash
./gradlew app:license
```

## Code Style Guidelines

### License Header
All source files must include the Apache 2.0 license header:
```java
/*
 * Copyright [year] Hippo Seven
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
```

### Naming Conventions

**Classes & Interfaces**: PascalCase
```java
public class GalleryListParser { }
public interface GalleryInfo { }
```

**Methods & Variables**: camelCase
```java
private int parsePages(Document d, String body) { }
private String mTextInputLayout;
val editText: EditText
```

**Constants**: SCREAMING_SNAKE_CASE
```java
private static final String TAG = GalleryListParser.class.getSimpleName();
private static final Pattern PATTERN_RATING = Pattern.compile("\\d+px");
```

**Package Names**: Lowercase, hierarchical
```java
package com.hippo.ehviewer.client.parser;
package com.hippo.util;
```

### Kotlin Conventions

**Properties**: camelCase, avoid `m` prefix
```kotlin
val editText: EditText
private var mDialog: AlertDialog? = null  // Legacy Java style acceptable
```

**Visibility Modifiers**: Use explicit modifiers
```kotlin
class EditTextDialogBuilder @SuppressLint("InflateParams") constructor(...)
private fun assertOrder(String s1, String s2) { }
```

**Null Safety**: Use nullable types and safe calls
```kotlin
private var mDialog: AlertDialog? = null
button?.performClick()
```

### Import Ordering

Organize imports in this order:
1. Android imports (`android.*`, `androidx.*`)
2. Third-party library imports
3. Internal project imports
4. Java/Kotlin standard library

```java
import android.annotation.SuppressLint;
import android.content.Context;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.textfield.TextInputLayout;
import com.hippo.ehviewer.R;
import org.jsoup.Jsoup;
import java.util.ArrayList;
```

### Annotation Usage

**Common Annotations**:
```java
// Suppress lint warnings when necessary
@SuppressLint("InflateParams")
@SuppressWarnings("unchecked")

// Nullability for Kotlin interop
@NonNull
@Nullable
```

### Error Handling

**Prefer Specific Exceptions**:
```java
import com.hippo.ehviewer.client.exception.ParseException;

throw new ParseException("Can't parse gallery list", body);
```

**Handle Fatal Errors**:
```java
import com.hippo.util.ExceptionUtils;

try {
    // code
} catch (Throwable e) {
    ExceptionUtils.throwIfFatal(e);
    // handle
}
```

**Logging**:
```java
private static final String TAG = GalleryListParser.class.getSimpleName();

Log.w(TAG, "Can't parse gallery info thumb size");
```

### Code Formatting

**Brace Style**: K&R style
```java
public void method() {
    if (condition) {
        doSomething();
    } else {
        doOther();
    }
}
```

**Line Length**: Soft limit around 120 characters

**Indentation**: 4 spaces (not tabs)

**Method Length**: Keep methods focused and under ~50 lines when possible

### Android-Specific Patterns

**ViewBinding Usage**:
```kotlin
val view = LayoutInflater.from(getContext()).inflate(R.layout.dialog_edittext_builder, null)
mTextInputLayout = view as TextInputLayout
editText = view.findViewById(R.id.edit_text)
```

**Activity/Fragment Extensions**:
```kotlin
fun Context.showErrorDialog(message: String) { }
fun Fragment.hideKeyboard() { }
```

**Resources Access**:
```kotlin
R.layout.activity_main
R.id.button_submit
R.string.app_name
```

### Testing Conventions

**Test Class Naming**: `[ClassName]Test`
```java
public class GalleryListParserTest { }
public class PathNaturalComparatorTest { }
```

**Test Method Naming**: camelCase or snake_case
```java
@Test
public void testOrder() { }
@Test
public void testEquals() { }
```

**Test Patterns**:
```java
private void assertOrder(String s1, String s2) {
    PathNaturalComparator comparator = new PathNaturalComparator();
    assertTrue(comparator.compare(s1, s2) < 0);
    assertTrue(comparator.compare(s2, s1) > 0);
}
```

**Test Dependencies**:
```gradle
testImplementation 'junit:junit:4.13.2'
testImplementation 'org.robolectric:robolectric:4.2.1'
testImplementation 'org.jooq:joor:0.9.6'
```

### Dependency Management

**Repository Configuration** (`settings.gradle`):
```gradle
repositories {
    google()
    mavenCentral()
    maven { url "https://jitpack.io" }
}
```

**Dependency Declaration** (`app/build.gradle`):
```gradle
implementation 'androidx.appcompat:appcompat:1.7.0'
implementation 'com.google.android.material:material:1.13.0'
implementation 'org.jsoup:jsoup:1.18.1'
```

### GreenDAO Configuration

Database entities use GreenDAO annotations:
```java
@Entity(nameInDb = "DOWNLOAD_INFO")
public class DownloadInfo {
    @Id
    private long gid;
    
    @NotNull
    private String label;
}
```

Generated classes are in `app/src/main/java-gen/`.

### Key Libraries

| Library | Purpose |
|---------|---------|
| OkHttp 3.14.7 | HTTP client |
| Jsoup 1.18.1 | HTML parsing |
| GreenDAO 3.0.0 | SQLite ORM |
| EventBus 3.3.1 | Event bus |
| Material Components | UI components |
| Firebase Crashlytics | Crash reporting |
| WorkManager 2.9.1 | Background tasks |

### CI/CD

GitHub Actions workflow (`.github/workflows/build.yml`):
- Builds on Ubuntu with JDK 21
- Runs `./gradlew app:assembleDebug`
- Uploads APK as artifact

## Common Tasks

### Adding a New Screen
1. Create Activity/Fragment in `app/src/main/java/com/hippo/ehviewer/ui/`
2. Create layout XML in `app/src/main/res/layout/`
3. Add to `AndroidManifest.xml`
4. Add navigation if needed

### Adding a New Download Source
1. Implement base download interface
2. Add parser for the source format
3. Integrate with `DownloadManager`
4. Add settings UI

### Modifying Database Schema
1. Update entity classes in `app/src/main/java/com/hippo/ehviewer/dao/`
2. Update `MSQLiteBuilder.java` for schema changes
3. Run tests to verify migration

## Git Commit Guidelines

Git commit messages must strictly adhere to structured, informative conventions. Vague one-liners, uninformative summaries, or rough commits without structured bodies are strictly prohibited.

### Format & Length Specifications

1. **Subject Line**: `<type>(<scope>): <concise Chinese summary>`
   - **Length**: **≤ 50 Chinese characters** (approx. 72 characters). One sentence summarizing the core objective. Never end with a period (`.`).
   - **Punctuation**: Follow `<type>(<scope>)` with a half-width colon and a space (`": "`).
2. **Blank Line**: **Exactly one empty line** must separate the subject line and the body.
3. **Body**:
   - **Two-Tier Hierarchical Structure (Pattern A / B)**: Use a **numbered list** with 2–4 main items. **All items must have a newline followed by a 3-space indented `- ` sub-bullet** (even single points must be indented on a new line to ensure strict visual uniformity):
     ```text
     1. <Topic / Module Name>:
        - <Specific technical changes and core explanation A>
        - <Specific technical changes and core explanation B (if applicable)>
     ```
     - Keep main topic descriptions within 40–100 characters; limit nested sub-bullets to 1–3 per item.
   - **Single-Tier Flat Structure (Pattern C)**: Use a **flat bullet list** (3–5 `- ` points) without nested tiers, concisely stating technical capabilities or changes:
     ```text
     - <Technical point / change explanation A>
     - <Technical point / change explanation B>
     ```
     - Keep each bullet point within 30–80 characters, clearly detailing concrete implementations.
   - **Line Wrapping & Visual Cleanliness**: Control bullet sentence length carefully. Strictly avoid awkward line wrapping where only 2–3 trailing characters spill over into an orphaned next line; ensure lines are either comfortably single-line or evenly balanced.
4. **Structural Pattern Selection (Flexible adaptation by nature of commit)**:
   - **Pattern A: Multi-Feature / Comprehensive Module (for `feat` / large-scale `refactor`)**: Divide main items by functional modules (e.g., 1. Core Feature, 2. UI/Interaction Refinement, 3. Architecture Refactoring).
   - **Pattern B: Single Defect / Cause-and-Effect Fix (for `fix` / `perf`)**: Structure items by the **investigation and resolution chain** (e.g., 1. Root Cause Analysis, 2. Core Mechanism Fix, 3. Business Scope / Coverage), avoiding forced artificial modularization.
   - **Pattern C: Self-Contained Feature / Flat Checklist (for standalone sub-systems, targeted `perf`, or `build` maintenance)**: When changes are highly cohesive under a single topic (e.g., introducing a logging system, targeted performance overhaul, dependency upgrade), use 3–5 flat bullet points to list system capabilities or optimizations cleanly without over-engineering layers.

### Common Types and Scopes

- **Type**:
  - `feat`: New feature or feature expansion
  - `fix`: Bug fix and logic correction
  - `perf`: Performance optimization, latency/memory reduction
  - `refactor`: Code refactoring without behavior changes
  - `build`: Gradle build scripts, dependency updates, configuration
  - `test`: Unit tests and testing infrastructure
- **Scope**:
  - `download`: Download list, download manager, labels, and grouping
  - `gallery`: Gallery detail, gallery list, reader
  - `gallerylog`: State transition and operation logs
  - `smb`: SMB network storage and migration
  - `settings`: Preferences and configuration storage

### Language & Writing Requirements

- **Output Language**: **Standard Chinese** for descriptive text; technical names, protocol abbreviations, and class names must preserve their original English casing (e.g., `GID`, `SQLite`, `SMB`, `CBZ`, `DownloadManager`).
- **Perspective**: Emphasize technical substance, root causes, and business impact. Avoid superficial narrations like "changed line X".
- **Signature Trailers**: Trailers such as `Co-authored-by` or `Signed-off-by` are allowed but strictly optional. Prioritize clarity and technical completeness.

## Important Notes

- This project has Chinese documentation and comments; respect them
- Firebase Crashlytics integration requires `google-services.json` in `app/`
- Some dependencies are forked/patched; check `build.gradle` forced versions
- SMB functionality uses `smbj` 0.11.5 (not latest) due to authentication issues
- WebP image support via custom native library in `app/src/main/cpp/`
