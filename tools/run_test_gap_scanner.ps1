param(
    [string]$SourceRoot = "src/main/java/com/landawn/abacus",
    [string]$TestRoot = "src/test/java/com/landawn/abacus"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$toolDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$scannerJava = Join-Path $toolDir "TestGapScanner.java"

javac $scannerJava
java -cp $toolDir TestGapScanner $SourceRoot $TestRoot
