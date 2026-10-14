const fs = require('fs');

// Minimal browser mocks for vendor bundle
global.window = global;
global.self = global;
global.document = {
    createElement: (tag) => ({
        style: {},
        setAttribute: () => {},
        appendChild: () => {},
        getElementsByTagName: () => [],
        addEventListener: () => {},
        removeEventListener: () => {}
    }),
    head: { appendChild: () => {} },
    documentElement: { style: {} },
    body: { appendChild: () => {} }
};
global.addEventListener = () => {};
global.removeEventListener = () => {};
global.navigator = { userAgent: 'Mozilla/5.0 (Linux; Android 14)' };

const vendorCode = fs.readFileSync('app/src/main/assets/assets/bundle/vendor.bundle.js', 'utf-8');
const runVendor = new Function('window', 'document', 'self', 'globalThis', vendorCode);
runVendor(global, global.document, global, global);

console.log("Loaded vendor.bundle.js successfully!");
console.log("zip object exists:", typeof global.zip);
console.log("Initial useWebWorkers:", global.zip.useWebWorkers);

// Force useWebWorkers to false
global.zip.useWebWorkers = false;

// Create a zip with deflate compression
const textContent = "function helloWorld() {\n    console.log('Testing pure JS zip extraction in Spck Editor');\n    return 42;\n}\n";

console.log("Creating zip writer...");
const abWriter = new global.zip.ArrayBufferWriter();

global.zip.createWriter(abWriter, function(zipWriter) {
    console.log("Writer created, adding entry with text...");
    const textReader = new global.zip.TextReader(textContent);
    zipWriter.add("game.js", textReader, function() {
        console.log("Entry added, closing zip writer...");
        zipWriter.close(function(arrayBuffer) {
            console.log("Zip closed! Buffer byteLength:", arrayBuffer.byteLength);
            
            // Now test reading it back using ArrayBufferReader with useWebWorkers = false
            console.log("Reading back entries...");
            global.zip.createReader(new global.zip.ArrayBufferReader(arrayBuffer), function(zipReader) {
                zipReader.getEntries(function(entries) {
                    console.log("Entries count:", entries.length);
                    console.log("Entry 0 filename:", entries[0].filename);
                    console.log("Entry 0 compressed:", entries[0].compressed);
                    console.log("Entry 0 uncompressedSize:", entries[0].uncompressedSize);
                    console.log("Entry 0 compressedSize:", entries[0].compressedSize);
                    
                    entries[0].getData(new global.zip.TextWriter(), function(extractedText) {
                        console.log("\n=== EXTRACTED TEXT ===");
                        console.log(extractedText);
                        console.log("Matches original:", extractedText === textContent);
                        if (extractedText === textContent) {
                            console.log("\n>>> SUCCESS: PURE JS INFLATE WORKS 100% PERFECTLY WITHOUT WEB WORKERS! <<<");
                        } else {
                            console.error(">>> FAILURE: Mismatch! <<<");
                        }
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
