// Mock browser globals for testing vendor.bundle.js zip extraction
const fs = require('fs');

global.window = global;
global.self = global;
global.Blob = class Blob {
    constructor(chunks) { this.chunks = chunks; }
};

// Read vendor.bundle.js
const vendorCode = fs.readFileSync('app/src/main/assets/assets/bundle/vendor.bundle.js', 'utf-8');
// Evaluate vendor.bundle.js
eval(vendorCode);

console.log("window.zip defined:", typeof global.zip);
console.log("zip.Inflater defined:", typeof global.zip.Inflater);
console.log("zip.Deflater defined:", typeof global.zip.Deflater);

global.zip.useWebWorkers = false;

// Create a small test zip file in memory using zip.createWriter
console.log("Testing zip creation and extraction without Web Workers...");

const blobWriter = new global.zip.BlobWriter();
global.zip.createWriter(blobWriter, function(writer) {
    const reader = new global.zip.TextReader("Hello Spck Editor World!");
    writer.add("test.txt", reader, function() {
        writer.close(function(blob) {
            console.log("ZIP created successfully in memory!");
            // Now test reading it back
            global.zip.createReader(new global.zip.BlobReader(blob), function(reader) {
                reader.getEntries(function(entries) {
                    console.log("Found entries count:", entries.length);
                    console.log("Entry 0 filename:", entries[0].filename);
                    entries[0].getData(new global.zip.TextWriter(), function(text) {
                        console.log("Entry extracted text:", text);
                        reader.close(function() {
                            console.log("SUCCESS! In-memory zip decompression works perfectly without workers!");
                        });
                    });
                });
            }, function(err) {
                console.error("Reader error:", err);
            });
        });
    });
}, function(err) {
    console.error("Writer error:", err);
});
