# WSL helpers. On Windows itself just use `dotnet run` / `dotnet publish` (see docs/development.md).

# Build the single-file exe into out/
publish:
    ~/.dotnet/dotnet publish -c Release -o out

# Publish, replace the installed copy (GLANCE_DIR or %USERPROFILE%\Apps\Glance) and relaunch it
deploy: publish
    #!/usr/bin/env bash
    set -e
    dir="${GLANCE_DIR:-$(wslpath "$(cmd.exe /c 'echo %USERPROFILE%' 2>/dev/null | tr -d '\r')")/Apps/Glance}"
    powershell.exe -NoProfile -Command "Get-Process Glance -ErrorAction SilentlyContinue | Where-Object Path -eq '$(wslpath -w "$dir/Glance.exe")' | Stop-Process; Start-Sleep 1"
    mkdir -p "$dir" && cp out/Glance.exe "$dir/"
    powershell.exe -NoProfile -Command "Start-Process '$(wslpath -w "$dir/Glance.exe")'"

# Publish and start a --demo copy from %TEMP% (runs alongside the real one)
demo: publish
    #!/usr/bin/env bash
    set -e
    dir="$(wslpath "$(cmd.exe /c 'echo %TEMP%' 2>/dev/null | tr -d '\r')")/glance-demo"
    powershell.exe -NoProfile -Command "Get-Process Glance -ErrorAction SilentlyContinue | Where-Object Path -eq '$(wslpath -w "$dir/Glance.exe")' | Stop-Process; Start-Sleep 1"
    mkdir -p "$dir" && cp out/Glance.exe "$dir/"
    powershell.exe -NoProfile -Command "Start-Process '$(wslpath -w "$dir/Glance.exe")' -ArgumentList '--demo'"
