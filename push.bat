@echo off
echo ====================================
echo      Pushing Code to GitHub
echo ====================================

:: Remove accidental quote/flag file if created
if exist " --date=local" del " --date=local"

:: Stage all changed and new files
git add .

:: Commit with fixed message quoting
git commit -m "Update Ktor combined endpoint Sheet1 A15 and Gradle fixes"

:: Push changes to main branch
git push origin main

echo ====================================
echo      Push Completed Successfully!
echo ====================================
pause