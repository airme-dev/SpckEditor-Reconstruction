const fs = require('fs');

global.window = global;
global.self = global;
global.document = {
    createElement: () => ({ style: {} }),
    head: { appendChild: () => {} }
};
global.navigator = { userAgent: 'node' };

const vendorCode = fs.readFileSync('app/src/main/assets/assets/bundle/vendor.bundle.js', 'utf-8');
eval(vendorCode);

console.log("window.zip defined:", typeof global.zip);
console.log("zip.Inflater defined:", typeof global.zip.Inflater);
console.log("zip.Deflater defined:", typeof global.zip.Deflater);
console.log("zip.useWebWorkers initial:", global.zip.useWebWorkers);

global.zip.useWebWorkers = false;

// Let's create an in-memory zip file with a compressed entry:
const writer = new global.zip.BlobWriter(); // BlobWriter or Uint8ArrayWriter
console.log("zip.ArrayBufferWriter:", typeof global.zip.ArrayBufferWriter);
console.log("zip.ArrayBufferReader:", typeof global.zip.ArrayBufferReader);
