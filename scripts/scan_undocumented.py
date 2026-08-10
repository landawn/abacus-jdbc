"""Heuristic scanner: find class-level members (fields, methods, ctors, nested types)
that lack a preceding Javadoc comment. Reports counts by visibility per file."""
import re
import os
import sys

BS = chr(92)  # backslash


def strip_strings_and_comments(lines):
    out = []
    in_block = False
    for line in lines:
        res = []
        i = 0
        n = len(line)
        while i < n:
            c = line[i]
            if in_block:
                if c == '*' and i + 1 < n and line[i + 1] == '/':
                    in_block = False
                    i += 2
                    continue
                i += 1
                continue
            if c == '/' and i + 1 < n and line[i + 1] == '*':
                in_block = True
                i += 2
                continue
            if c == '/' and i + 1 < n and line[i + 1] == '/':
                break
            if c == '"':
                if line[i:i + 3] == '"""':
                    j = line.find('"""', i + 3)
                    i = n if j < 0 else j + 3
                    continue
                i += 1
                while i < n and line[i] != '"':
                    i += 2 if line[i] == BS else 1
                i += 1
                continue
            if c == "'":
                i += 1
                while i < n and line[i] != "'":
                    i += 2 if line[i] == BS else 1
                i += 1
                continue
            res.append(c)
            i += 1
        out.append(''.join(res))
    return out


TYPE_RE = re.compile(r'\b(class|interface|enum|@interface)\s+\w+')
MOD_RE = re.compile(
    r'^(public|protected|private|static|final|abstract|synchronized|default|native|transient|volatile|strictfp)\b')
KW_RE = re.compile(
    r'^(return|if|for|while|switch|else|do|try|catch|new|throw|case|break|continue|synchronized)\b')
DECL_RE = re.compile(r'\w+\s*(\(|=|;)')


def scan(path):
    raw = open(path, encoding='utf-8').read().splitlines()
    lines = strip_strings_and_comments(raw)
    depth = 0
    type_stack = []  # body depths of enclosing types; None until its '{' seen
    hits = []
    for idx, line in enumerate(lines):
        stripped = line.strip()
        cur = depth
        is_type = bool(TYPE_RE.search(stripped)) and not stripped.startswith((')', '.', ','))
        body_depths = [d for d in type_stack if d is not None]
        if cur in body_depths and stripped and not stripped.startswith(('*', '//', '@', '}')):
            if is_type or ((MOD_RE.match(stripped) or re.match(r'^\w[\w<>\[\],.?]*\s+\w+\s*(=|;|\()', stripped))
                           and not KW_RE.match(stripped) and DECL_RE.search(stripped)):
                # look upward in raw for javadoc end; also detect @Override
                j = idx - 1
                found = False
                override = False
                while j >= 0 and j > idx - 40:
                    t = raw[j].strip()
                    if t == '':
                        j -= 1
                        continue
                    if t.startswith('@'):
                        if t.startswith('@Override'):
                            override = True
                        j -= 1
                        continue
                    if t.endswith('*/'):
                        found = True
                    break
                if not found and not override:
                    if re.match(r'^public\b', stripped):
                        vis = 'pub'
                    elif re.match(r'^protected\b', stripped):
                        vis = 'prot'
                    else:
                        vis = 'other'
                    kind = 'type' if is_type else 'member'
                    hits.append((idx + 1, vis + ':' + kind, raw[idx].strip()[:120]))
        opened_type = False
        if is_type:
            type_stack.append(None)
            opened_type = True
        for ch in line:
            if ch == '{':
                depth += 1
                if opened_type and type_stack and type_stack[-1] is None:
                    type_stack[-1] = depth
            elif ch == '}':
                depth -= 1
                while type_stack and type_stack[-1] is not None and type_stack[-1] > depth:
                    type_stack.pop()
    return hits


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else 'src/main/java'
    verbose = '-v' in sys.argv
    total = 0
    targets = []
    if os.path.isfile(root):
        targets.append(root)
    else:
        for dirpath, _dirs, files in os.walk(root):
            for f in sorted(files):
                if f.endswith('.java'):
                    targets.append(os.path.join(dirpath, f))
    for path in sorted(targets):
        hits = scan(path)
        if not hits:
            continue
        total += len(hits)
        pubs = [h for h in hits if h[1].startswith('pub')]
        prots = [h for h in hits if h[1].startswith('prot')]
        others = [h for h in hits if h[1].startswith('other')]
        print(f"{len(hits):4d} (pub {len(pubs)}, prot {len(prots)}, other {len(others)})  {path}")
        if verbose:
            for ln, vis, txt in hits:
                print(f"      {ln:5d} [{vis}] {txt}")
    print('TOTAL', total)


if __name__ == '__main__':
    main()
