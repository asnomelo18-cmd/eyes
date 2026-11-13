@echo off
rem Lance Eyes PC sans fenetre de console.
cd /d "%~dp0"
start "" ".venv\Scripts\pythonw.exe" -m eyes_pc
