#!/usr/bin/env python3
"""Convert PDF to DOCX using pdf2docx. Invoked by Java backend."""
import sys


def main() -> int:
    if len(sys.argv) != 3:
        print("Usage: pdf_to_docx.py <input.pdf> <output.docx>", file=sys.stderr)
        return 2
    inp, out = sys.argv[1], sys.argv[2]
    try:
        from pdf2docx import Converter
    except ImportError:
        print(
            "pdf2docx 未安装。请执行: pip install -r backend/requirements-pdf2docx.txt",
            file=sys.stderr,
        )
        return 3
    cv = Converter(inp)
    try:
        cv.convert(out)
    finally:
        cv.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
