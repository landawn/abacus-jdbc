#!/usr/bin/env python3
"""
Fix Javadoc formatting issues line by line.
Handles:
1. Double blank * lines before/after Usage Examples
2. Usage Examples placed after @param/@return/@throws instead of before
"""

import re
import os
import sys

def is_blank_star(line):
    return bool(re.match(r'^[ \t]*\*[ \t]*$', line))

def is_tag(line):
    return bool(re.match(r'^[ \t]*\* @(?:param|return|throws|see|since|deprecated)\s', line))

def is_usage_header(line):
    return '<p><b>Usage Examples' in line

def get_star_indent(line):
    """Get the whitespace before * in a javadoc line."""
    m = re.match(r'^([ \t]*)\*', line)
    return m.group(1) if m else ''

def fix_file(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()
    
    original = content
    lines = content.split('\n')
    new_lines = list(lines)
    
    # We'll process with a while loop since we may modify the list
    i = 0
    changes = 0
    
    while i < len(new_lines) - 2:
        line = new_lines[i]
        line2 = new_lines[i+1]
        line3 = new_lines[i+2] if i+2 < len(new_lines) else ''
        
        # Fix 1: Double blank stars before Usage Examples header
        if (is_blank_star(line) and is_blank_star(line2) and is_usage_header(line3)):
            del new_lines[i]
            changes += 1
            continue
        
        # Fix 2: Usage Examples after @tags
        if is_tag(line):
            # Collect all consecutive @tag lines
            tag_start = i
            j = i
            while j < len(new_lines) and is_tag(new_lines[j]):
                j += 1
            tag_end = j - 1
            
            # After @tags, check for: (optional blank) -> Usage Examples block -> */
            pos = tag_end + 1
            
            # Skip blank lines between @tags and Usage Examples
            while pos < len(new_lines) and is_blank_star(new_lines[pos]):
                pos += 1
            
            # Check if next non-blank line is Usage Examples header
            if pos < len(new_lines) and is_usage_header(new_lines[pos]):
                # Found Usage Examples after @tags
                usage_start = pos
                
                # Find the end of Usage Examples block (</pre> line)
                usage_end = usage_start
                while usage_end < len(new_lines):
                    if '</pre>' in new_lines[usage_end]:
                        break
                    usage_end += 1
                
                if usage_end < len(new_lines):
                    # Extract sections
                    prefix = new_lines[:tag_start]          # before @tags
                    tag_lines = new_lines[tag_start:tag_end+1]  # @tag lines
                    # The Usage Examples block (including blank before it if needed)
                    usage_lines = new_lines[usage_start:usage_end+1]
                    after = new_lines[usage_end+1:]         # after </pre>
                    
                    # Get the indent from @tags for the Usage Examples block
                    tag_indent = get_star_indent(tag_lines[0])
                    
                    # Get current indent of Usage Examples
                    usage_indent = get_star_indent(usage_lines[0])
                    
                    # Re-indent Usage Examples to match @tag indentation
                    if tag_indent != usage_indent and tag_indent:
                        reindented = []
                        for uline in usage_lines:
                            m = re.match(r'^([ \t]*)\*', uline)
                            if m:
                                reindented.append(tag_indent + '*' + uline[m.end():])
                            else:
                                reindented.append(uline)
                        usage_lines = reindented
                    
                    # Build the new structure:
                    # prefix + blank * + Usage Examples + blank * + @tags + after
                    blank = tag_indent + '*'
                    
                    new_lines = (prefix + 
                                [blank] + 
                                usage_lines + 
                                [blank] + 
                                tag_lines + 
                                after)
                    
                    changes += 1
                    # Continue from where we left off (after the inserted content)
                    i = tag_start - 1
                    continue
        
        i += 1
    
    content = '\n'.join(new_lines)
    
    # Also check for double blanks after </pre> (which we might have created)
    # Replace: </pre>\n     *\n     *\n     * @... with </pre>\n     *\n     * @...
    # This is harder to detect, but our fix 1 above should handle it on next run
    
    if content != original:
        with open(filepath, 'w', encoding='utf-8') as f:
            f.write(content)
        return True, changes
    return False, 0

def main():
    base_path = sys.argv[1] if len(sys.argv) > 1 else 'src/main/java'
    
    files = []
    if os.path.isfile(base_path) and base_path.endswith('.java'):
        files = [base_path]
    elif os.path.isdir(base_path):
        for root, dirs, filenames in os.walk(base_path):
            if '.git' in root:
                continue
            for fname in filenames:
                if fname.endswith('.java') and fname != 'package-info.java':
                    files.append(os.path.join(root, fname))
    else:
        print(f'Path not found: {base_path}')
        sys.exit(1)
    
    print(f'Processing {len(files)} files...')
    
    total_fixed = 0
    total_changes = 0
    for fpath in sorted(files):
        try:
            fixed, count = fix_file(fpath)
            if fixed:
                rel = os.path.relpath(fpath, base_path)
                print(f'  Fixed ({count}): {rel}')
                total_fixed += 1
                total_changes += count
        except Exception as e:
            print(f'  Error: {fpath}: {e}')
            import traceback
            traceback.print_exc()
    
    print(f'\nTotal files fixed: {total_fixed}/{len(files)}, total changes: {total_changes}')

if __name__ == '__main__':
    main()
