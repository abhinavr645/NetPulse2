# Render the original SVG asset to PNG and a multi-resolution Windows ICO.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$projectRoot = Split-Path -Parent $PSScriptRoot
$iconDirectory = Join-Path $projectRoot 'packaging\windows'
$resourceDirectory = Join-Path $projectRoot 'src\main\resources\icons'
[xml]$svg = Get-Content -Raw -LiteralPath (Join-Path $resourceDirectory 'netpulse.svg')
New-Item -ItemType Directory -Path $iconDirectory -Force | Out-Null
$images = New-Object 'System.Collections.Generic.List[byte[]]'
$sizes = @(16,24,32,48,64,128,256)
foreach ($size in $sizes) {
    $bitmap = New-Object Drawing.Bitmap $size,$size
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    $memory = New-Object IO.MemoryStream
    try {
        $graphics.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $graphics.ScaleTransform(($size/256.0),($size/256.0))
        foreach ($node in $svg.DocumentElement.ChildNodes) {
            if ($node.LocalName -eq 'rect') {
                $x=[single]$node.x; $y=[single]$node.y
                $width=[single]$node.width; $height=[single]$node.height; $diameter=2*[single]$node.rx
                $shape = New-Object Drawing.Drawing2D.GraphicsPath
                $brush = New-Object Drawing.SolidBrush ([Drawing.ColorTranslator]::FromHtml($node.fill))
                try {
                    $shape.AddArc($x,$y,$diameter,$diameter,180,90)
                    $shape.AddArc(($x+$width-$diameter),$y,$diameter,$diameter,270,90)
                    $shape.AddArc(($x+$width-$diameter),($y+$height-$diameter),$diameter,$diameter,0,90)
                    $shape.AddArc($x,($y+$height-$diameter),$diameter,$diameter,90,90)
                    $shape.CloseFigure()
                    $graphics.FillPath($brush,$shape)
                    if ($node.stroke) {
                        $pen=New-Object Drawing.Pen ([Drawing.ColorTranslator]::FromHtml($node.stroke)),([single]$node.'stroke-width')
                        try { $graphics.DrawPath($pen,$shape) } finally { $pen.Dispose() }
                    }
                } finally { $brush.Dispose(); $shape.Dispose() }
            } elseif ($node.LocalName -eq 'polyline') {
                $points = [Drawing.PointF[]]@($node.points.Trim().Split(' ') | Where-Object { $_ } | ForEach-Object {
                    $xy=$_.Split(','); New-Object Drawing.PointF ([single]$xy[0]),([single]$xy[1])
                })
                $pen=New-Object Drawing.Pen ([Drawing.ColorTranslator]::FromHtml($node.stroke)),([single]$node.'stroke-width')
                try {
                    $pen.StartCap=[Drawing.Drawing2D.LineCap]::Round; $pen.EndCap=[Drawing.Drawing2D.LineCap]::Round
                    $pen.LineJoin=[Drawing.Drawing2D.LineJoin]::Round
                    $graphics.DrawLines($pen,$points)
                } finally { $pen.Dispose() }
            } elseif ($node.LocalName -eq 'circle') {
                $brush=New-Object Drawing.SolidBrush ([Drawing.ColorTranslator]::FromHtml($node.fill))
                try { $graphics.FillEllipse($brush,([single]$node.cx-[single]$node.r),([single]$node.cy-[single]$node.r),(2*[single]$node.r),(2*[single]$node.r)) }
                finally { $brush.Dispose() }
            }
        }
        $bitmap.Save($memory,[Drawing.Imaging.ImageFormat]::Png)
        $images.Add($memory.ToArray())
        if ($size -eq 256) { [IO.File]::WriteAllBytes((Join-Path $resourceDirectory 'netpulse.png'),$memory.ToArray()) }
    } finally { $memory.Dispose(); $graphics.Dispose(); $bitmap.Dispose() }
}
$file=[IO.File]::Create((Join-Path $iconDirectory 'NetPulse.ico'))
$writer=New-Object IO.BinaryWriter $file
try {
    $writer.Write([uint16]0); $writer.Write([uint16]1); $writer.Write([uint16]$sizes.Count)
    $offset=6+16*$sizes.Count
    for ($index=0; $index -lt $sizes.Count; $index++) {
        $dimension=if ($sizes[$index] -eq 256) { 0 } else { $sizes[$index] }
        $writer.Write([byte]$dimension); $writer.Write([byte]$dimension)
        $writer.Write([byte]0); $writer.Write([byte]0); $writer.Write([uint16]1); $writer.Write([uint16]32)
        $writer.Write([uint32]$images[$index].Length); $writer.Write([uint32]$offset)
        $offset += $images[$index].Length
    }
    foreach ($bytes in $images) { $writer.Write($bytes) }
} finally { $writer.Dispose(); $file.Dispose() }
