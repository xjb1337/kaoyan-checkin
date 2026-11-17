---
name: android-win-gradle
description: Run Android unit tests in this repo on Windows PowerShell without gradlew.bat.
---

# Windows PowerShell 运行 Android 单元测试

1. `android/` 目录仅包含 `gradlew`（无 `gradlew.bat`），在 Windows 下直接通过 `gradle-wrapper.jar` 启动：
   ```powershell
   $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
   cd e:\kaoyan\kaoyan-checkin\android
   java "-Dorg.gradle.appname=gradlew" -classpath "gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain testDebugUnitTest
   ```
2. 若遇到 `build-tools` 或 `platforms` 许可未接受错误，先在 Android Studio SDK Manager 或 `sdkmanager --licenses` 完成许可确认后再运行。
