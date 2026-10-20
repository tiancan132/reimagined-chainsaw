@echo off
chcp 65001 >nul
title Duyue Reader - Upload to GitHub

cd /d "%~dp0"

echo ============================================
echo   Duyue Reader - Upload to GitHub
echo ============================================
echo.

git branch -M main 2>nul
git remote remove origin 2>nul
git remote add origin https://github.com/tiancan132/reimagined-chainsaw.git

echo Uploading...
echo.
echo If a login window pops up:
echo   1. Choose "Sign in with your browser"
echo   2. Click the green "Authorize" button in browser
echo.

git push -u origin main

echo.
echo ============================================
echo  Done - check: https://github.com/tiancan132/reimagined-chainsaw
echo ============================================
echo.
echo Press any key to close...
pause >nul
