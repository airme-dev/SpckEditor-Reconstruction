with open('app/src/main/assets/assets/bundle/app.bundle.js', 'r', encoding='utf-8', errors='replace') as f:
    text = f.read()

idx = 197126
print(text[idx:idx+1200].encode('ascii', errors='replace').decode('ascii'))
