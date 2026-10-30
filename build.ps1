#
# 下载中心插件 —— 构建脚本
#
# 用法：
#   powershell -NoProfile -ExecutionPolicy Bypass -File build.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File build.ps1 -HMCLJar "D:\HMCL.jar"
#

param(
    [string]$HMCLJar = "",
    [string]$OutDir = "dist"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

# 1. 定位启动器 jar：命令行参数 > 环境变量 > 常见位置
if (-not $HMCLJar) {
    if ($env:HMCL_JAR) {
        $HMCLJar = $env:HMCL_JAR
    } else {
        $candidates = @(
            (Join-Path $root "..\..\HMCL-test\HMCL-3.16.4-downloadhub.jar"),
            (Join-Path $root "..\..\HMCL-test\HMCL-3.16.4.jar"),
            (Join-Path $root "..\..\HMCL\HMCL\build\libs\HMCL-3.16.4.SNAPSHOT.jar")
        )
        foreach ($candidate in $candidates) {
            if (Test-Path $candidate) {
                $HMCLJar = (Resolve-Path $candidate).Path
                break
            }
        }
    }
}

if (-not $HMCLJar -or -not (Test-Path $HMCLJar)) {
    Write-Error "找不到启动器 jar。请用 -HMCLJar 指定，或设置 HMCL_JAR 环境变量。"
}

Write-Host "启动器：$HMCLJar"

# 2. 定位 JavaFX：优先用启动器依赖目录里的版本
$fxJars = @()
$fxDirs = @(
    (Join-Path (Split-Path $HMCLJar -Parent) ".hmcl\dependencies"),
    (Join-Path $root "..\..\HMCL-test\.hmcl\dependencies")
)
foreach ($dir in $fxDirs) {
    if (-not (Test-Path $dir)) { continue }
    $found = Get-ChildItem $dir -Recurse -Filter "javafx-*-win.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match "javafx-(base|controls|graphics)" } |
        Select-Object -First 3
    if ($found) {
        $fxJars = $found.FullName
        break
    }
}

if ($fxJars.Count -eq 0) {
    Write-Warning "未找到 JavaFX jar，编译可能失败。请确认启动器目录下有 .hmcl\dependencies。"
}

$classpath = (@($HMCLJar) + $fxJars) -join ";"

# 3. 编译
$outPath = Join-Path $root $OutDir
$classes = Join-Path $outPath "classes"
Remove-Item $classes -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classes | Out-Null

$sources = Get-ChildItem (Join-Path $root "src") -Recurse -Filter "*.java" | Select-Object -ExpandProperty FullName
Write-Host "编译 $($sources.Count) 个源文件…"

& javac -encoding UTF-8 --release 17 -cp $classpath -d $classes $sources
if ($LASTEXITCODE -ne 0) {
    Write-Error "编译失败"
}

# 4. 复制资源与清单
$manifest = Get-Content (Join-Path $root "hmcl.plugin.json") -Raw -Encoding UTF8 | ConvertFrom-Json
Copy-Item (Join-Path $root "hmcl.plugin.json") $classes -Force

$resDir = Join-Path $root "res"
if (Test-Path $resDir) {
    Copy-Item "$resDir\*" $classes -Recurse -Force
}

# 5. 打包
$jarName = "$($manifest.id)_$($manifest.version).jar"
$jarPath = Join-Path $outPath $jarName
Remove-Item $jarPath -Force -ErrorAction SilentlyContinue

& jar --create --file $jarPath -C $classes .
if ($LASTEXITCODE -ne 0) {
    Write-Error "打包失败"
}

$size = [math]::Round((Get-Item $jarPath).Length / 1KB, 1)
Write-Host ""
Write-Host "构建完成：$jarPath ($size KB)" -ForegroundColor Green
