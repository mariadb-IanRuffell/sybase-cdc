# EXECUTE over ODBC (C++)

`execute_odbc_demo.cpp` calls the WASM-backed `SybaseWasmJob` from plain ODBC with the prototype SQL `EXECUTE`
statement (see `../gridgain-9-execute-statement.patch`). The JSON response comes back in the single `RESULT` column.

1. Build the ODBC driver from the GridGain 9 source with the prototype branch checked out (the prebuilt pygridgain
   driver 9.0.17 crashes inside unixODBC, because it bundles its own copy of it):

       cmake <gridgain-9>/modules/platforms/cpp -DENABLE_CLIENT=OFF -DENABLE_ODBC=ON -DCMAKE_BUILD_TYPE=Release
       cmake --build . -j 8        # produces lib/libgridgain9-odbc.dylib

2. Start a node with the schema, seed data and job unit (stays up until `build/stop-execute-server` exists):

       JAVA_HOME=$(/usr/libexec/java_home -v 21) <gridgain-9>/gradlew -p <gridgain-9> -I "$PWD/../source-build.gradle" \
         -Dsybase.example.dir="$(cd .. && pwd)" -Dsybase.main=example.sybase.ExecuteDemoServer \
         :sybase-wasm-example:run --console=plain

3. Build and run the client (needs unixODBC headers, e.g. `brew install unixodbc`):

       ./build.sh
       ODBC_DRIVER=/path/to/libgridgain9-odbc.dylib ../build/execute_odbc_demo

`GG_ADDRESS` overrides the default `127.0.0.1:10890`. `ARGS` is a string literal, not a bind parameter, so the client
doubles single quotes in the JSON.
