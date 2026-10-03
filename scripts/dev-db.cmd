@echo off
rem Same as "scripts\dev.cmd db": keep data in a SQLite file (.local\data\fleet-analysis.db).
rem (Double-click this file. Keep this file ASCII only; see scripts/README.md)
call "%~dp0dev.cmd" db
