param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [string]$Gradle
)

$ErrorActionPreference = 'Stop'
$project = $PSScriptRoot
$build = (New-Item -ItemType Directory -Path (Join-Path $project '.build') -Force).FullName
if (!$JavaHome) {
    $localJdk = Get-ChildItem -LiteralPath (Join-Path $build 'jdk') -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin\javac.exe') } |
        Select-Object -First 1
    $JavaHome = $localJdk.FullName
}
if (!$AndroidSdk) { $AndroidSdk = Join-Path $build 'sdk' }
if (!$JavaHome) { throw 'Set JAVA_HOME or pass -JavaHome with a JDK 17+ installation.' }
if (!$Gradle) {
    $Gradle = Join-Path $build 'tooling\gradle-8.11.1\bin\gradle.bat'
    if (!(Test-Path -LiteralPath $Gradle)) { $Gradle = (Get-Command gradle.bat -ErrorAction SilentlyContinue).Source }
}
if (!$Gradle) { throw 'Install Gradle 8.11.1 or pass -Gradle with the path to gradle.bat.' }
$androidJar = Join-Path $AndroidSdk 'platforms\android-35\android.jar'
if (!(Test-Path -LiteralPath $androidJar)) { throw 'Install the Android 35 platform into the SDK.' }
$tools = Join-Path $AndroidSdk 'build-tools\35.0.1'
if (!(Test-Path -LiteralPath $tools)) { $tools = Join-Path $AndroidSdk 'build-tools\android-15' }
if (!(Test-Path -LiteralPath $tools)) { throw 'Install Android build-tools 35.0.1 into the SDK.' }

function Invoke-BuildTool([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$(Split-Path -Leaf $Program) exited with code $LASTEXITCODE." }
}

$java = Join-Path $JavaHome 'bin\java.exe'
$keytool = Join-Path $JavaHome 'bin\keytool.exe'
$zipalign = Join-Path $tools 'zipalign.exe'
$apksigner = Join-Path $tools 'lib\apksigner.jar'

$previousJavaHome = $env:JAVA_HOME
$previousAndroidHome = $env:ANDROID_HOME
$previousSdkRoot = $env:ANDROID_SDK_ROOT
$work = (New-Item -ItemType Directory -Path (Join-Path $build ('apk-' + [Guid]::NewGuid().ToString('N')))).FullName
$unsigned = Join-Path $build 'gradle-app\outputs\apk\release\app-release-unsigned.apk'
$aligned = Join-Path $work 'aligned.apk'
$signed = Join-Path $work 'signed.apk'
$target = Join-Path $project '练有数.apk'

try {
    $env:JAVA_HOME = $JavaHome
    $env:ANDROID_HOME = $AndroidSdk
    $env:ANDROID_SDK_ROOT = $AndroidSdk
    Invoke-BuildTool $Gradle @('--no-daemon','--gradle-user-home',(Join-Path $build 'gradle-user'),
        '--project-cache-dir',(Join-Path $build 'gradle-cache'),'-p',$project,':app:assembleRelease')
    Invoke-BuildTool $zipalign @('-f','-P','16','4',$unsigned,$aligned)

    $signing = (New-Item -ItemType Directory -Path (Join-Path $build 'signing') -Force).FullName
    $key = Join-Path $signing 'lianji.p12'
    $passwordFile = Join-Path $signing 'password.txt'
    if (!(Test-Path -LiteralPath $key)) {
        if (!(Test-Path -LiteralPath $passwordFile)) {
            $secret = [byte[]]::new(24)
            [System.Security.Cryptography.RandomNumberGenerator]::Fill($secret)
            [IO.File]::WriteAllText($passwordFile, [Convert]::ToHexString($secret), [Text.Encoding]::ASCII)
        }
        Invoke-BuildTool $keytool @('-genkeypair','-alias','lianji','-keystore',$key,'-storetype','PKCS12',
            '-storepass:file',$passwordFile,'-keyalg','RSA','-keysize','3072','-validity','10000',
            '-dname','CN=Lianji App','-noprompt')
    }
    if (!(Test-Path -LiteralPath $passwordFile)) { throw 'The signing keystore requires its original password.txt.' }
    Invoke-BuildTool $java @('-jar',$apksigner,'sign','--ks',$key,'--ks-key-alias','lianji',
        '--ks-pass',"file:$passwordFile",'--v4-signing-enabled','false','--out',$signed,$aligned)
    Invoke-BuildTool $java @('-jar',$apksigner,'verify','--verbose',$signed)
    Invoke-BuildTool $zipalign @('-c','-P','16','4',$signed)
    Copy-Item -LiteralPath $signed -Destination $target -Force
    Get-Item -LiteralPath $target | Select-Object FullName,Length
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:ANDROID_HOME = $previousAndroidHome
    $env:ANDROID_SDK_ROOT = $previousSdkRoot
    $resolvedWork = (Resolve-Path -LiteralPath $work).Path
    if ($resolvedWork.StartsWith($build + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $resolvedWork -Recurse -Force
    }
}
