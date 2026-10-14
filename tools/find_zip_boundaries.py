with open('app/src/main/assets/assets/bundle/vendor.bundle.js', 'r', encoding='utf-8', errors='replace') as f:
    text = f.read()

idx = text.find('useWebWorkers:!0')
# find the function start before idx
f_start = text.rfind('(function(', 0, idx)
print('zip IIFE starts at:', f_start)
print(text[f_start:f_start+300])

f_end = text.find('useWebWorkers:!0', f_start)
f_end = text.find('}(this)', f_end) + len('}(this)')
print('zip IIFE ends at:', f_end)
print(text[f_end-100:f_end+50])
