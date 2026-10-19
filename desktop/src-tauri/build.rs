fn main() {
    // The app's own commands, so capabilities/updater.json can allow them for the update window only.
    tauri_build::try_build(
        tauri_build::Attributes::new()
            .app_manifest(tauri_build::AppManifest::new().commands(&["update_info", "start_update", "dismiss_update"])),
    )
    .expect("failed to run the Tauri build script");
}
