win := "/mnt/c/Users/Bosire/Apps/Glance"

# Build the single-file exe into out/
publish:
    ~/.dotnet/dotnet publish -c Release -o out

# Build, replace the running copy in C:\Users\Bosire\Apps\Glance and relaunch it
deploy: publish
    -taskkill.exe /IM Glance.exe /F
    mkdir -p {{win}} && cp out/Glance.exe {{win}}/
    powershell.exe -NoProfile -Command "Start-Process 'C:\Users\Bosire\Apps\Glance\Glance.exe'"
