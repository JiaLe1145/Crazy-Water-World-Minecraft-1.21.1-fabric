# Full-screen capture to PNG. The agent has no vision, so this is how it "sees" the game for verification.
# NOTE: keep this file ASCII-only. Windows PowerShell 5.1 decodes BOM-less UTF-8 as GBK and
# non-ASCII comments can break parsing (same trap documented in AI建模/安装说明.md item 10).
param(
    [string]$Out = "$PWD\run\shot.png"
)
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

$bounds = [System.Windows.Forms.SystemInformation]::VirtualScreen
$bmp = New-Object System.Drawing.Bitmap $bounds.Width, $bounds.Height
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen($bounds.Location, [System.Drawing.Point]::Empty, $bounds.Size)

$full = [System.IO.Path]::GetFullPath($Out)
$dir = Split-Path -Parent $full
if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
$bmp.Save($full, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose()
$bmp.Dispose()
Write-Output "saved: $full"
