with open('app/src/main/assets/assets/bundle/vendor.bundle.js', 'r', encoding='utf-8', errors='replace') as f:
    content = f.read()

idx = content.find('useWebWorkers:!0')
print('useWebWorkers:!0 found at:', idx)
print(content[idx - 200:idx + 200].encode('ascii', errors='replace').decode('ascii'))
