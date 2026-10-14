with open('app/src/main/assets/assets/bundle/vendor.bundle.js', 'r', encoding='utf-8', errors='replace') as f:
    content = f.read()

idx = content.find('A(new t.zip.Inflater')
print('A(new t.zip.Inflater found at:', idx)
print(content[idx - 50:idx + 300].encode('ascii', errors='replace').decode('ascii'))
