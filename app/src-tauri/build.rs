// The app id is stamped in from tauri.conf.json at build time, so no
// application id ever appears as a literal in the Rust sources.
fn read_app_id() -> String {
    let text =
        std::fs::read_to_string("tauri.conf.json").expect("tauri.conf.json must sit next to Cargo.toml");
    let key = "\"identifier\"";
    let from = text.find(key).expect("tauri.conf.json must declare an identifier");
    let after_key = &text[from + key.len()..];
    let colon = after_key.find(':').expect("identifier entry must hold a value");
    let mut chars = after_key[colon + 1..].trim_start().chars();
    assert!(chars.next() == Some('"'), "identifier must be a JSON string");
    let mut id = String::new();
    let mut closed = false;
    while let Some(c) = chars.next() {
        if c == '"' {
            closed = true;
            break;
        }
        if c == '\\' {
            let e = chars.next().expect("identifier holds a dangling escape");
            id.push(match e {
                '"' => '"',
                '\\' => '\\',
                '/' => '/',
                'n' => '\n',
                't' => '\t',
                _ => panic!("identifier holds an unsupported escape"),
            });
        } else {
            id.push(c);
        }
    }
    assert!(closed && !id.is_empty(), "identifier must not be empty");
    id
}

fn main() {
    // Android 15+ devices may use 16 KB memory pages: align the native library's LOAD segments to 16 KB.
    // A build-script link arg applies even when the Tauri CLI sets RUSTFLAGS.
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("android") {
        println!("cargo:rustc-link-arg=-Wl,-z,max-page-size=16384");
    }
    println!("cargo:rerun-if-changed=tauri.conf.json");
    println!("cargo:rustc-env=ITONAMI_APP_ID={}", read_app_id());
    tauri_build::try_build(tauri_build::Attributes::new().app_manifest(
        tauri_build::AppManifest::new().commands(&["awai_host_info"]),
    ))
    .expect("dougaka Studio build");
}
