// The generated Android buildSrc runs `node tauri android android-studio-script`
// with src-tauri as cwd, so `node tauri` must resolve from here. Forward to
// the real Tauri CLI installed in app/node_modules.
import path from "node:path";
import { createRequire } from "node:module";
const require = createRequire(import.meta.url);
require(path.join(process.cwd(), "..", "node_modules", "@tauri-apps", "cli", "tauri.js"));
