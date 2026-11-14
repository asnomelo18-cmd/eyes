@echo off
rem Compile la derniere version d'Eyes et l'installe sur le telephone branche en USB.
rem Les jumelages et les reglages du telephone sont conserves.
cd /d "%~dp0"
set "JAVA_HOME=C:\Users\LENOVO\AppData\Local\Programs\Eclipse Adoptium\jdk-17.0.21.9-hotspot"
set "ADB=C:\Users\LENOVO\AppData\Local\Android\sdk\platform-tools\adb.exe"

echo === 1/3 Compilation de l'application...
call "%~dp0gradlew.bat" assembleDebug -q
if errorlevel 1 (
    echo.
    echo La compilation a echoue. Rien n'a ete installe.
    pause
    exit /b 1
)

echo === 2/3 Recherche du telephone...
"%ADB%" get-state >nul 2>&1
if errorlevel 1 (
    echo.
    echo Aucun telephone detecte. Branchez-le en USB, deverrouillez-le et autorisez le debogage USB.
    echo L'application compilee est ici : app\build\outputs\apk\debug\app-debug.apk
    pause
    exit /b 1
)

echo === 3/3 Installation sur le telephone...
"%ADB%" install -r app\build\outputs\apk\debug\app-debug.apk
if errorlevel 1 (
    echo.
    echo L'installation a echoue. Acceptez la demande d'installation sur le telephone si elle s'affiche.
    pause
    exit /b 1
)

echo.
echo Termine. Sur le telephone : ouvrez Eyes et reactivez "Pret a partager".
pause
