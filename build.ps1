param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidSdk = $env:ANDROID_HOME
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
$javac = Join-Path $JavaHome 'bin\javac.exe'
$jar = Join-Path $JavaHome 'bin\jar.exe'
$keytool = Join-Path $JavaHome 'bin\keytool.exe'
$aapt = Join-Path $tools 'aapt2.exe'
$zipalign = Join-Path $tools 'zipalign.exe'
$d8 = Join-Path $tools 'lib\d8.jar'
$apksigner = Join-Path $tools 'lib\apksigner.jar'

$work = (New-Item -ItemType Directory -Path (Join-Path $build ('apk-' + [Guid]::NewGuid().ToString('N')))).FullName
$classes = (New-Item -ItemType Directory -Path (Join-Path $work 'classes')).FullName
$dex = (New-Item -ItemType Directory -Path (Join-Path $work 'dex')).FullName
$source = Join-Path $project 'app\src\main'
$resources = Join-Path $work 'resources.zip'
$unsigned = Join-Path $work 'unsigned.apk'
$aligned = Join-Path $work 'aligned.apk'
$signed = Join-Path $work 'signed.apk'
$compiled = Join-Path $work 'classes.jar'
$target = Join-Path $project '练有数.apk'

try {
    Invoke-BuildTool $aapt @('compile','--dir',(Join-Path $source 'res'),'-o',$resources)
    Invoke-BuildTool $aapt @('link','-o',$unsigned,'-I',$androidJar,'--manifest',
        (Join-Path $source 'AndroidManifest.xml'),$resources)
    $javaFiles = @(Get-ChildItem -LiteralPath (Join-Path $source 'java') -Recurse -Filter '*.java' -File |
        Select-Object -ExpandProperty FullName)
    Invoke-BuildTool $javac (@('--release','8','-Xlint:-options','-encoding','UTF-8','-classpath',$androidJar,'-d',$classes) + $javaFiles)
    Invoke-BuildTool $jar @('cf',$compiled,'-C',$classes,'.')
    Invoke-BuildTool $java @('-cp',$d8,'com.android.tools.r8.D8','--release','--min-api','26','--lib',$androidJar,'--output',$dex,$compiled)
    Invoke-BuildTool $jar @('uf',$unsigned,'-C',$dex,'classes.dex')
    Invoke-BuildTool $zipalign @('-f','4',$unsigned,$aligned)

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
    Invoke-BuildTool $zipalign @('-c','4',$signed)
    Copy-Item -LiteralPath $signed -Destination $target -Force
    Get-Item -LiteralPath $target | Select-Object FullName,Length
} finally {
    $resolvedWork = (Resolve-Path -LiteralPath $work).Path
    if ($resolvedWork.StartsWith($build + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $resolvedWork -Recurse -Force
    }
}
