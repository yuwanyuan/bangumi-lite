import re
import sys
sys.stdout.reconfigure(encoding='utf-8')

with open(r'd:\1\worker\bgm\tag_list.html', 'r', encoding='utf-8') as f:
    html = f.read()

tagRegex = re.compile(r'<a[^>]*href="[^"]*/tag/[^"]*"[^>]*>([^<]+)</a>\s*<small[^>]*>\((\d+)\)</small>')
matches = tagRegex.findall(html)
print(f'Found {len(matches)} tags')
for i, m in enumerate(matches[:5]):
    print(f'  {i+1}: name={m[0]}, count={m[1]}')
