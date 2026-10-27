@echo off
echo ====================================
echo      Pushing Code to GitHub
echo ====================================

:: Remove accidental quote/flag file if created
if exist " --date=local" del " --date=local"

:: Ask for Commit Message dynamically
set /p commit_msg="Enter your commit comment: "

:: If message is empty, set a default message
if "%commit_msg%"=="" set commit_msg="Update Ktor API and code fixes"

:: Stage all changed and new files
git add .

:: Commit with user entered message
git commit -m "%commit_msg%"

:: Push changes to main branch
git push origin main

echo ====================================
echo      Push Completed Successfully!
echo ====================================
pause
