$project = $PSScriptRoot
$frontend = Join-Path $PSScriptRoot "frontend"

Start-Process "C:\Users\mlzyuan\AppData\Roaming\Python\Python312\Scripts\milvus-lite.exe" `
    -ArgumentList "server","--port","19530","--data-dir",(Join-Path $project "milvus_data")
Write-Host "[1/3] Milvus started" -ForegroundColor Green

Start-Sleep 3

Start-Process cmd -WorkingDirectory $project -ArgumentList "/k","mvn spring-boot:run"
Write-Host "[2/3] Backend starting..." -ForegroundColor Yellow

Start-Sleep 30

Start-Process cmd -WorkingDirectory $frontend -ArgumentList "/k","npx vite"
Write-Host "[3/3] Frontend starting..." -ForegroundColor Yellow

Start-Sleep 4
Start-Process "http://localhost:5173"
Write-Host "Done. visit http://localhost:5173" -ForegroundColor Cyan
