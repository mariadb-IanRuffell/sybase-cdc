// Calls the WASM-backed SybaseWasmJob through ODBC using the SQL EXECUTE statement (GridGain prototype).
//
//   EXECUTE 'example.sybase.SybaseWasmJob' UNITS 'sybase-wasm-example:1.0.0' ARGS '<json request>'
//
// The statement returns one row with one VARCHAR column, RESULT, holding the job's JSON response.
// Needs a running node: see README.md (ExecuteDemoServer).

#include <sql.h>
#include <sqlext.h>

#include <cstdlib>
#include <iostream>
#include <stdexcept>
#include <string>

static const char *JOB = "example.sybase.SybaseWasmJob";
static const char *UNIT = "sybase-wasm-example:1.0.0";

static std::string diag(SQLSMALLINT type, SQLHANDLE h) {
    SQLCHAR state[8], msg[1024];
    SQLINTEGER native;
    SQLSMALLINT len;
    if (SQL_SUCCEEDED(SQLGetDiagRec(type, h, 1, state, &native, msg, sizeof(msg), &len)))
        return std::string(reinterpret_cast<char *>(state)) + ": " + reinterpret_cast<char *>(msg);
    return "(no diagnostics)";
}

static void check(SQLRETURN rc, SQLSMALLINT type, SQLHANDLE h, const char *what) {
    if (!SQL_SUCCEEDED(rc))
        throw std::runtime_error(std::string(what) + " failed - " + diag(type, h));
}

/** Doubles single quotes: ARGS is a string literal, not a bind parameter. */
static std::string quote(const std::string &s) {
    std::string out;
    for (char c : s) {
        if (c == '\'') out += '\'';
        out += c;
    }
    return out;
}

static std::string execute_sql(const std::string &json) {
    return std::string("EXECUTE '") + JOB + "' UNITS '" + UNIT + "' ARGS '" + quote(json) + "'";
}

/** Runs one EXECUTE and returns the RESULT column. */
static std::string call(SQLHDBC dbc, const std::string &request) {
    SQLHSTMT stmt = SQL_NULL_HSTMT;
    check(SQLAllocHandle(SQL_HANDLE_STMT, dbc, &stmt), SQL_HANDLE_DBC, dbc, "SQLAllocHandle(STMT)");
    try {
        std::string sql = execute_sql(request);
        check(SQLExecDirect(stmt, (SQLCHAR *) sql.c_str(), SQL_NTS), SQL_HANDLE_STMT, stmt, "SQLExecDirect");
        check(SQLFetch(stmt), SQL_HANDLE_STMT, stmt, "SQLFetch");
        std::string result;
        char buf[8192];
        SQLLEN ind = 0;
        SQLRETURN rc;
        while ((rc = SQLGetData(stmt, 1, SQL_C_CHAR, buf, sizeof(buf), &ind)) == SQL_SUCCESS_WITH_INFO || rc == SQL_SUCCESS) {
            result.append(buf, ind > 0 && ind < (SQLLEN) sizeof(buf) ? (size_t) ind : sizeof(buf) - 1);
            if (rc == SQL_SUCCESS) break;
        }
        SQLFreeHandle(SQL_HANDLE_STMT, stmt);
        return result;
    } catch (...) {
        SQLFreeHandle(SQL_HANDLE_STMT, stmt);
        throw;
    }
}

static bool contains(const std::string &s, const std::string &needle) { return s.find(needle) != std::string::npos; }

static void require(bool ok, const std::string &msg) {
    if (!ok) throw std::runtime_error("CHECK FAILED: " + msg);
}

int main() {
    const char *driver = std::getenv("ODBC_DRIVER");
    const char *address = std::getenv("GG_ADDRESS");
    std::string conn_str = std::string("DRIVER={") + (driver ? driver : "GridGain 9") + "};ADDRESS="
                           + (address ? address : "127.0.0.1:10890") + ";";

    SQLHENV env = SQL_NULL_HENV;
    SQLHDBC dbc = SQL_NULL_HDBC;
    SQLAllocHandle(SQL_HANDLE_ENV, SQL_NULL_HANDLE, &env);
    SQLSetEnvAttr(env, SQL_ATTR_ODBC_VERSION, (void *) SQL_OV_ODBC3, 0);
    SQLAllocHandle(SQL_HANDLE_DBC, env, &dbc);
    try {
        SQLCHAR out[1024];
        SQLSMALLINT out_len;
        check(SQLDriverConnect(dbc, nullptr, (SQLCHAR *) conn_str.c_str(), SQL_NTS, out, sizeof(out), &out_len,
                               SQL_DRIVER_NOPROMPT), SQL_HANDLE_DBC, dbc, "SQLDriverConnect");

        // 1. Read-only procedure.
        std::string c360 = call(dbc, R"({"procedure":"sp_customer_360","parameters":{"customer_id":1}})");
        require(contains(c360, "\"status\":0"), c360);
        require(contains(c360, "Lovelace"), c360);
        std::cout << std::unitbuf << "sp_customer_360: status=0, contains customer 'Lovelace' (" << c360.size() << " bytes of JSON)\n";

        // 2. Expected business failure is a normal result.
        std::string missing = call(dbc, R"({"procedure":"sp_customer_360","parameters":{"customer_id":999}})");
        require(contains(missing, "\"status\":-1"), missing);
        std::cout << std::unitbuf << "sp_customer_360 (unknown customer): status=-1\n";

        // 3. Writing procedure, then a SELECT on the same connection.
        std::string created = call(dbc, R"({"procedure":"sp_create_order","parameters":{"customer_id":1}})");
        require(contains(created, "\"status\":0"), created);
        auto pos = created.find("\"order_id\":");
        require(pos != std::string::npos, created);
        int order = std::atoi(created.c_str() + pos + 11);

        SQLHSTMT stmt;
        SQLAllocHandle(SQL_HANDLE_STMT, dbc, &stmt);
        std::string select = "SELECT status FROM orders WHERE order_id = " + std::to_string(order);
        check(SQLExecDirect(stmt, (SQLCHAR *) select.c_str(), SQL_NTS), SQL_HANDLE_STMT, stmt, "SELECT");
        check(SQLFetch(stmt), SQL_HANDLE_STMT, stmt, "SELECT fetch");
        char status[64];
        SQLLEN ind;
        SQLGetData(stmt, 1, SQL_C_CHAR, status, sizeof(status), &ind);
        SQLFreeHandle(SQL_HANDLE_STMT, stmt);
        require(std::string(status) == "NEW", std::string("order status: ") + status);

        std::string item = call(dbc, "{\"procedure\":\"sp_add_order_item\",\"parameters\":{\"order_id\":" + std::to_string(order)
                                         + ",\"product_id\":1,\"qty\":2}}");
        require(contains(item, "\"status\":0"), item);
        std::cout << std::unitbuf << "sp_create_order + sp_add_order_item: order " << order << " created (status NEW), item added\n";

        // 4. Unknown unit surfaces as an ODBC error.
        try {
            SQLAllocHandle(SQL_HANDLE_STMT, dbc, &stmt);
            std::string bad = std::string("EXECUTE '") + JOB + "' UNITS 'no-such-unit:1.0.0' ARGS '{}'";
            SQLRETURN rc = SQLExecDirect(stmt, (SQLCHAR *) bad.c_str(), SQL_NTS);
            if (SQL_SUCCEEDED(rc)) throw std::runtime_error("CHECK FAILED: unknown unit was accepted");
            std::cout << std::unitbuf << "unknown unit rejected: " << diag(SQL_HANDLE_STMT, stmt).substr(0, 140) << "\n";
            SQLFreeHandle(SQL_HANDLE_STMT, stmt);
        } catch (...) {
            throw;
        }

        std::cout << std::unitbuf << "EXECUTE-OVER-ODBC CHECKS PASSED\n";
        SQLDisconnect(dbc);
    } catch (const std::exception &e) {
        std::cerr << "ERROR: " << e.what() << "\n";
        SQLFreeHandle(SQL_HANDLE_DBC, dbc);
        SQLFreeHandle(SQL_HANDLE_ENV, env);
        return 1;
    }
    SQLFreeHandle(SQL_HANDLE_DBC, dbc);
    SQLFreeHandle(SQL_HANDLE_ENV, env);
    return 0;
}
