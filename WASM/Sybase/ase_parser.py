"""Parse SAP ASE procedure scripts into a Lark AST with source positions."""

import argparse
import json
import re
import sys
from functools import lru_cache
from pathlib import Path

from lark import Lark, Token, UnexpectedInput


ROOT = Path(__file__).resolve().parent

# Skip delimiters inside quoted text and line comments when locating nested comments.
SQL_DELIMITER = re.compile(r"""--[^\r\n]*|'(?:''|[^'])*'|"(?:""|[^"])*"|\[(?:\]\]|[^\]])*\]|/\*""")
COMMENT_DELIMITER = re.compile(r"/\*|\*/")


def mask_comments(source):
    """Keep all offsets (including CRLFs) so diagnostics refer to the original SQL."""
    if "/*" not in source:
        return source
    parts = []
    position = 0
    search_from = 0
    while match := SQL_DELIMITER.search(source, search_from):
        search_from = match.end()
        if match[0] != "/*":
            continue
        depth = 1
        for marker in COMMENT_DELIMITER.finditer(source, search_from):
            depth += 1 if marker[0] == "/*" else -1
            if depth == 0:
                search_from = marker.end()
                break
        else:
            line = source.count("\n", 0, match.start()) + 1
            column = match.start() - source.rfind("\n", 0, match.start())
            raise ValueError(f"Unclosed block comment at line {line}, column {column}")
        parts.append(source[position:match.start()])
        parts.append(re.sub(r"[^\r\n]", " ", source[match.start():search_from]))
        position = search_from
    return "".join(parts) + source[position:]


class QuotedIdentifiers:
    """Classify double quotes using the initial setting and SET statements in this script."""

    always_accept = ()

    def __init__(self, initially_on):
        self.initially_on = initially_on

    def process(self, tokens):
        enabled = self.initially_on
        previous = before_previous = ""
        for token in tokens:
            if enabled and token.type == "DOUBLE_QUOTED":
                token = Token.new_borrow_pos("DELIMITED_NAME", token, token)
            word = token.lower()
            if before_previous == "set" and previous == "quoted_identifier" and word in ("on", "off"):
                enabled = word == "on"
            before_previous, previous = previous, word
            yield token


@lru_cache(maxsize=2)
def parser(quoted_identifiers=False):
    return Lark.open(str(ROOT / "ase.lark"), parser="lalr", lexer="basic",
                     propagate_positions=True, postlex=QuotedIdentifiers(quoted_identifiers))


def parse_sql(source, *, quoted_identifiers=False):
    return parser(quoted_identifiers).parse(mask_comments(source))


def as_dict(node):
    if isinstance(node, Token):
        return {"kind": node.type, "text": str(node), "start": node.start_pos, "end": node.end_pos}
    result = {"kind": str(node.data), "children": [as_dict(child) for child in node.children]}
    if not node.meta.empty:
        result.update(start=node.meta.container_start_pos, end=node.meta.container_end_pos,
                      line=node.meta.container_line, column=node.meta.container_column)
    return result


def syntax_error(source, error):
    return f"line {error.line}, column {error.column}\n{error.get_context(source)}"


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("source", type=Path)
    cli.add_argument("--json", action="store_true", help="include raw tokens and source spans")
    cli.add_argument("--quoted-identifiers", action="store_true", help="start with quoted_identifier on")
    args = cli.parse_args()
    try:
        source = args.source.read_bytes().decode("utf-8-sig")
        ast = parse_sql(source, quoted_identifiers=args.quoted_identifiers)
    except UnexpectedInput as exc:
        sys.exit(f"{args.source}: {syntax_error(source, exc)}")
    except (OSError, ValueError) as exc:
        sys.exit(f"{args.source}: {exc}")
    print(json.dumps(as_dict(ast), indent=2, ensure_ascii=False) if args.json else ast.pretty().rstrip())
