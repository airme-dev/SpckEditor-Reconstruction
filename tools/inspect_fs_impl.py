with open('app/src/main/assets/assets/bundle/app.bundle.js', 'r', encoding='utf-8', errors='replace') as f:
    text = f.read()

idx = 171384
print(text[idx-2000:idx+2000].encode('ascii', errors='replace').decode('ascii'))
