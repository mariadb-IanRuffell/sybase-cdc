#!/usr/bin/env bash
# Builds execute_odbc_demo against unixODBC (Homebrew: brew install unixodbc).
set -euo pipefail
cd "$(dirname "$0")"
prefix=${ODBC_PREFIX:-$(brew --prefix unixodbc 2>/dev/null || echo /usr/local)}
mkdir -p ../build
${CXX:-c++} -std=c++17 -Wall -I"$prefix/include" execute_odbc_demo.cpp -L"$prefix/lib" -lodbc -o ../build/execute_odbc_demo
echo "built ../build/execute_odbc_demo"
