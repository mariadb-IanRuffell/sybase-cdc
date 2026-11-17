"""Compile the executable subset of the SAP ASE AST to WASM and Ignite SQL bindings."""

import argparse
import json
import re
import sys
from pathlib import Path

from lark import Token, Tree, UnexpectedInput
from wasmtime import Engine, Module, wat2wasm

from ase_parser import ROOT, parse_sql, syntax_error


COMPARE = {">": "gt_s", "<": "lt_s", ">=": "ge_s", "<=": "le_s", "=": "eq"}


class UnsupportedSQL(ValueError):
    def __init__(self, node, expected):
        location = node.meta if isinstance(node, Tree) else node
        kind = node.data if isinstance(node, Tree) else node.type
        super().__init__(f"Parsed {kind} at line {location.line}, column {location.column}. "
                         f"The WASM backend requires {expected}.")


def fields(node, kind, count):
    if not isinstance(node, Tree) or node.data != kind or len(node.children) != count:
        raise UnsupportedSQL(node, f"{kind} with {count} operands")
    return node.children


def identifier(token):
    if not isinstance(token, Token) or not re.fullmatch(r"[A-Za-z_][A-Za-z_0-9]*", token):
        raise UnsupportedSQL(token, "a simple identifier")
    return str(token)


def simple_name(node):
    token, = fields(node, "object_name", 1)
    return identifier(token)


def integer(node):
    sign = 1
    if node.data in ("negate", "positive"):
        sign = -1 if node.data == "negate" else 1
        node, = node.children
    value, = fields(node, "integer", 1)
    number = sign * int(value)
    if not -(2**31) <= number < 2**31:
        raise ValueError(f"Literal outside the SQL INT range: {number}")
    return number


def unqualified_name(node):
    """Map an unqualified or dbo-qualified ASE object to the default Ignite namespace."""
    parts = node.children
    if len(parts) == 2 and str(parts[0]).lower() == "dbo":
        return identifier(parts[1])
    return simple_name(node)


def ignite_query(query, temporaries):
    """Emit parameterized Ignite SQL. Record scalar and snapshot arguments in placeholder order."""
    arguments = []
    core = query.children[0]
    into = next((c for c in core.children if c.data == "into_table"), None)
    prefix = {"select_core": "SELECT", "from_clause": "FROM", "where_clause": "WHERE",
              "group_by": "GROUP BY", "having": "HAVING", "order_by": "ORDER BY",
              "column_alias": "AS", "alias": "AS", "else_value": "ELSE"}
    comma_lists = {"select_list", "expr_list", "order_by", "from_clause"}

    def render(node):
        if isinstance(node, Token):
            return identifier(node)
        kind, children = node.data, node.children
        if kind in ("local_var", "system_variable"):
            arguments.append(str(children[0]).lower())
            return "CAST(? AS INT)"
        if kind == "object_name":
            return ".".join(identifier(part) for part in children)
        if kind == "table_reference":
            table, *modifiers = children
            if len(modifiers) > 1 or (modifiers and modifiers[0].data != "alias"):
                raise UnsupportedSQL(node, "a table reference with an optional alias")
            logical = str(table.children[0]).lower()
            if logical.startswith("#"):
                if len(table.children) != 1 or logical not in temporaries:
                    raise UnsupportedSQL(table, "a previously materialized local temporary table")
                cols = temporaries[logical]
                arguments.extend(f"{logical}.{col}" for col in cols)
                source = "TABLE(" + ", ".join(f"{col} BIGINT = ?" for col in cols) + ")"
            else:
                source = unqualified_name(table)
            return " ".join([source, *(render(m) for m in modifiers)])
        if kind == "integer":
            return str(integer(node))
        if kind == "string":
            raw = str(children[0])
            if raw.startswith("'"):
                return raw
            if raw.startswith('"'):
                return "'" + raw[1:-1].replace('""', '"').replace("'", "''") + "'"
            raise UnsupportedSQL(node, "a quoted string literal")
        if kind == "comparison_op":
            op = str(children[0])
            if op not in ("=", "<", ">", ">=", "<=", "<>", "!="):
                raise UnsupportedSQL(node, "a portable SQL comparison")
            return op
        if kind == "direction":
            return str(children[0]).upper()
        if kind == "function_call":
            fn, args = fields(node, kind, 2)
            name = simple_name(fn).upper()
            if name not in ("SUM", "COUNT", "MAX", "MIN", "AVG"):
                raise UnsupportedSQL(fn, "a supported aggregate function")
            return f"{name}({render(args)})"
        if kind in ("star_arg", "star"):
            return "*"
        if kind == "searched_when":
            cond, value = children
            return f"WHEN {render(cond)} THEN {render(value)}"
        if kind == "case_expr":
            if children[0].data != "searched_when":
                raise UnsupportedSQL(node, "a searched CASE expression")
            return "CASE " + " ".join(render(c) for c in children) + " END"
        if kind in prefix or kind in ("query", "select_list", "expr_list", "select_item", "column",
                                      "joined_table", "comparison", "order_item"):
            parts = [render(child) for child in children if child is not into]
            text = (", " if kind in comma_lists else " ").join(parts)
            return (prefix[kind] + " " if kind in prefix else "") + text
        raise UnsupportedSQL(node, "a supported Ignite SELECT expression or clause")

    sql = render(query)
    operation = {"kind": "result_set", "arguments": arguments}
    if into is not None:
        table, = fields(into, "into_table", 1)
        target, = fields(table, "object_name", 1)
        if not re.fullmatch(r"#[A-Za-z_][A-Za-z_0-9]*", str(target)):
            raise UnsupportedSQL(into, "SELECT INTO a local #temporary table")
        target = target.lower()
        columns = []
        projections = next(c for c in core.children if c.data == "select_list")
        for item in projections.children:
            last = item.children[-1] if item.children else None
            if item.data == "select_item" and last.data == "column_alias":
                col = last.children[0]
            elif item.data == "select_item" and last.data == "column":
                col = last.children[0].children[-1]
            else:
                raise UnsupportedSQL(item, "a named column or aliased expression in SELECT INTO")
            columns.append(identifier(col).lower())
        if len(set(columns)) != len(columns) or target in temporaries:
            raise UnsupportedSQL(into, "unique snapshot and column names")
        temporaries[target] = columns
        operation.update(kind="snapshot", target=target, columns=columns)
    return sql, operation


def lower(procedure, abi=None):
    header = list(procedure.children)
    if header[0].data == "replace_option":
        header.pop(0)
    name_node, *modifiers, body = header
    name = unqualified_name(name_node)
    params = []
    defaults = {}
    outputs = []
    bindings = {}
    operations = {}
    temporaries = {}
    for modifier in modifiers:
        if modifier.data != "parameters":
            raise UnsupportedSQL(modifier, "ordinary procedure parameters")
        for declaration in modifier.children:
            var, datatype, *options = declaration.children
            type_name, = fields(datatype, "datatype", 1)
            if simple_name(type_name).lower() != "int" or not re.fullmatch(r"@[A-Za-z_][A-Za-z_0-9]*", var):
                raise UnsupportedSQL(declaration, "a named INT parameter")
            key = var[1:].lower()
            if key in params:
                raise ValueError(f"Duplicate procedure parameter {var}")
            params.append(key)
            for option in options:
                if option.data == "default_argument":
                    defaults[key] = integer(option.children[0])
                elif option.data == "output_mode":
                    outputs.append(key)
                else:
                    raise UnsupportedSQL(option, "an integer default or OUTPUT")

    def local_key(node):
        var, = fields(node, "local_var", 1)
        key = var[1:].lower()
        if key not in params:
            raise ValueError(f"Undeclared parameter {var}")
        return key

    def operand(node):
        if node.data == "local_var":
            return f"local.get ${local_key(node)}"
        if node.data == "system_variable" and node.children[0].lower() == "@@rowcount":
            return "local.get $@@rowcount"
        if node.data in ("integer", "negate", "positive"):
            return f"i32.const {integer(node)}"
        raise UnsupportedSQL(node, "an INT literal, an input parameter, or @@rowcount")

    def comparison(node):
        left, op, right = fields(node, "comparison", 3)
        return left, str(op.children[0]), right

    def return_values(status, indent):
        return [indent + status, *(indent + f"local.get ${key}" for key in outputs), indent + "return"]

    def emit(node, indent):
        if node.data in ("block", "statements"):
            return [line for stmt in node.children for line in emit(stmt, indent)]
        if node.data == "if_statement":
            condition, branch = fields(node, "if_statement", 2)
            left, operator, right = comparison(condition)
            if operator not in COMPARE:
                raise UnsupportedSQL(condition, "a supported integer comparison")
            return [
                indent + operand(left),
                indent + operand(right),
                indent + "i32." + COMPARE[operator],
                # ASE's IF reads the previous count, then clears it on either path.
                indent + "i32.const 0",
                indent + "local.set $@@rowcount",
                indent + "if",
                *emit(branch, indent + "  "),
                indent + "end",
            ]
        if node.data == "return_status":
            return return_values(operand(node.children[0]) if node.children else "i32.const 0", indent)
        if node.data == "set_options":
            if (len(node.children) == 2 and str(node.children[0]).lower() == "nocount"
                    and getattr(node.children[1], "data", None) == "on_off"):
                # NOCOUNT controls protocol messages. It does not suppress SELECT row sets.
                return [indent + "i32.const 0", indent + "local.set $@@rowcount"]
            raise UnsupportedSQL(node, "SET NOCOUNT ON or OFF")
        if node.data == "query":
            if any(node.find_data("variable_assignment")):
                core, = fields(node, "query", 1)
                projections, = fields(core, "select_core", 1)
                assignment, = fields(projections, "select_list", 1)
                dest, value = fields(assignment, "variable_assignment", 2)
                return [indent + operand(value), indent + f"local.set ${local_key(dest)}",
                        indent + "i32.const 1", indent + "local.set $@@rowcount"]
            sql, op = ignite_query(node, temporaries)
        elif node.data == "update":
            table, assignments, where = fields(node, "update", 3)
            assignment, = fields(assignments, "assignments", 1)
            target, addition = fields(assignment, "assignment", 2)
            column_ref, increment = fields(addition, "add", 2)
            column, = fields(column_ref, "column", 1)
            predicate, = fields(where, "where_clause", 1)
            key_ref, operator, key_value = comparison(predicate)
            if operator != "=":
                raise UnsupportedSQL(predicate, "WHERE key = @parameter")
            key, = fields(key_ref, "column", 1)
            sql = (f"UPDATE {simple_name(table)} SET {simple_name(target)} = "
                   f"{simple_name(column)} + ? WHERE {simple_name(key)} = ?")
            op = {"kind": "update", "arguments": ["@" + local_key(increment), "@" + local_key(key_value)]}
        else:
            raise UnsupportedSQL(node, "a statement supported by the WASM backend")

        imported = f"{op['kind']}_{len(bindings)}"
        bindings[imported] = sql
        operations[imported] = op
        instructions = []
        for arg in op["arguments"]:
            if arg.startswith("#"):
                continue  # Snapshot columns are bound by the host.
            if arg == "@@rowcount":
                instructions.append("local.get $@@rowcount")
            elif arg[1:] in params:
                instructions.append(f"local.get ${arg[1:]}")
            else:
                raise UnsupportedSQL(node, f"a declared scalar in place of {arg}")
        instructions.extend([f"call ${imported}", "local.set $@@rowcount"])
        return [indent + instruction for instruction in instructions]

    instructions = emit(body, "    ")
    if not instructions or instructions[-1].strip() != "return":
        instructions.extend(return_values("i32.const 0", "    "))
    imports = []
    for key, op in operations.items():
        count = sum(arg.startswith("@") for arg in op["arguments"])
        arguments = " (param " + " ".join(["i32"] * count) + ")" if count else ""
        imports.append(f'  (import "ignite" "{key}" (func ${key}{arguments} (result i32)))')
    signature = " ".join(f"(param ${param} i32)" for param in params)
    result_types = " ".join(["i32"] * (1 + len(outputs)))
    wat = "\n".join([
        "(module", *imports, f'  (func (export "{name}") {signature} (result {result_types})',
        "    (local $@@rowcount i32)", *instructions, "  )", ")", "",
    ])
    if abi is not None:
        abi.update(name=name, parameters=params, defaults=defaults, outputs=outputs, operations=operations)
    return wat, bindings


def compile_file(source, output):
    script = parse_sql(source.read_bytes().decode("utf-8-sig"))
    batches = [node for node in script.children if node.data == "batch"]
    if len(batches) != 1 or batches[0].children[0].data != "procedure":
        raise ValueError("WASM compilation requires exactly one procedure. Use ase_parser.py to inspect scripts.")
    procedure = batches[0].children[0]
    abi = {}
    wat, bindings = lower(procedure, abi)
    wasm = wat2wasm(wat)
    Module.validate(Engine(), wasm)
    name = abi["name"]
    output.mkdir(parents=True, exist_ok=True)
    (output / f"{name}.ast.txt").write_text(script.pretty())
    (output / f"{name}.wat").write_text(wat)
    (output / f"{name}.bindings.json").write_text(json.dumps(bindings, indent=2) + "\n")
    (output / f"{name}.abi.json").write_text(json.dumps(abi, indent=2) + "\n")
    (output / f"{name}.wasm").write_bytes(wasm)
    return name


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("source", type=Path)
    cli.add_argument("--out", type=Path, default=ROOT / "generated")
    args = cli.parse_args()
    try:
        proc = compile_file(args.source, args.out)
    except UnexpectedInput as exc:
        sys.exit(f"{args.source}: {syntax_error(args.source.read_text(), exc)}")
    except (ValueError, OSError) as exc:
        sys.exit(f"{args.source}: {exc}")
    print(f"Compiled {proc} to {args.out}")
