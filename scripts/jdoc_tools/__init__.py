"""
jdoc_tools
==========

Javadoc "Usage Examples" reporting / normalization toolkit.

This package is a consolidated Python rewrite of the ~50 throwaway Node.js
scripts that used to live under ``scripts/codex/`` (which were written by codex
for a *different* codebase, ``com.landawn.abacus.util``). The behaviour is
preserved; the sprawl is not. Everything is driven through a single CLI:

    python scripts/jdoc.py <command> [options] [PATH ...]

Modules
-------
region    : low-level Javadoc-block / code-block primitives (the shared lib).
reports   : read-only checks (never write); each exits non-zero on findings.
fixes     : in-place fixers; dry-run by default, ``--apply`` to write.
pipeline  : eligible-file discovery + validate-all / cleanup-all / git helpers.
"""

__all__ = ["region", "reports", "fixes", "pipeline"]
