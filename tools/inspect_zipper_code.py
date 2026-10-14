with open('app/src/main/assets/assets/bundle/app.bundle.js', 'r', encoding='utf-8', errors='replace') as f:
    text = f.read()

idx = 448204
print(text[idx:idx+6000].encode('ascii', errors='replace').decode('ascii'))
