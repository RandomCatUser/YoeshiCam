$sdkPath = "$env:LOCALAPPDATA\Android\Sdk".Replace('\', '\\')
Set-Content -Path "local.properties" -Value "sdk.dir=$sdkPath"

$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"

Write-Host "Running Gradle with Java 17 (from Android Studio JBR)..."
.\gradle-8.4\bin\gradle.bat assembleDebug
