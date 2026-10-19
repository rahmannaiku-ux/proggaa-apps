//! Proggaa for Windows: one window showing prooggaa.com, plus the things a
//! browser tab can't do — its own icon and taskbar entry, one copy at a
//! time, remembering its size, and updating itself from GitHub Releases.
//!
//! The site gets no access to Tauri's API (there is no capability for remote
//! pages): everything the app does natively happens here in Rust.

use std::time::Duration;

use tauri::{AppHandle, Manager, WebviewUrl, WebviewWindowBuilder};
use tauri_plugin_dialog::{DialogExt, MessageDialogButtons, MessageDialogKind};
use tauri_plugin_opener::OpenerExt;
use tauri_plugin_updater::UpdaterExt;
use url::Url;

/// The site the app shows.
pub const SITE: &str = "https://prooggaa.com";

/// A link the site uses (on /download, when it sees it is inside the app)
/// to ask the app to look for an update now.
const CHECK_UPDATE_URL: &str = "proggaa://update";

/// Wait this long after launch before the first update check, so it never
/// slows the site down while it loads.
const FIRST_CHECK_AFTER: Duration = Duration::from_secs(10);
/// Look again this often while the app stays open.
const CHECK_EVERY: Duration = Duration::from_secs(6 * 60 * 60);

/// Where the app's own window may go. Anything else (YouTube, Facebook, a
/// link in a post...) opens in the person's normal browser instead.
pub fn opens_in_app(url: &Url) -> bool {
    match url.scheme() {
        // The bundled start page, and in-page things the site creates itself.
        "tauri" | "about" | "data" | "blob" => return true,
        "http" | "https" => {}
        _ => return false,
    }
    let host = url.host_str().unwrap_or_default();
    host == "tauri.localhost"
        || host == "prooggaa.com"
        || host.ends_with(".prooggaa.com")
        // Admins and mentors connecting Google Drive / Docs sign in to Google and come back.
        || host == "accounts.google.com"
}

/// The browser identity the site sees: an ordinary Edge on Windows, plus a
/// "ProggaaDesktop/<version>" tag the site uses to know it is in the app.
fn user_agent(version: &str) -> String {
    format!(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) \
         Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0 ProggaaDesktop/{version}"
    )
}

pub fn run() {
    tauri::Builder::default()
        // A second launch just brings the open window to the front.
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.unminimize();
                let _ = window.show();
                let _ = window.set_focus();
            }
        }))
        .plugin(tauri_plugin_window_state::Builder::default().build())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .setup(|app| {
            let handle = app.handle().clone();
            let version = app.package_info().version.to_string();
            let nav_handle = handle.clone();

            WebviewWindowBuilder::new(app, "main", WebviewUrl::App("index.html".into()))
                .title("Proggaa")
                .inner_size(1280.0, 820.0)
                .min_inner_size(420.0, 560.0)
                .center()
                .user_agent(&user_agent(&version))
                .on_navigation(move |url| {
                    if url.as_str().starts_with(CHECK_UPDATE_URL) {
                        let app = nav_handle.clone();
                        tauri::async_runtime::spawn(async move { check_for_update(&app, true).await });
                        return false;
                    }
                    if opens_in_app(url) {
                        return true;
                    }
                    if matches!(url.scheme(), "http" | "https" | "mailto" | "tel") {
                        let _ = nav_handle.opener().open_url(url.as_str(), None::<&str>);
                    }
                    false
                })
                .build()?;

            tauri::async_runtime::spawn(async move {
                tokio::time::sleep(FIRST_CHECK_AFTER).await;
                loop {
                    check_for_update(&handle, false).await;
                    tokio::time::sleep(CHECK_EVERY).await;
                }
            });
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("Proggaa could not start");
}

/// Shows a message box without blocking the async runtime; true = the first button.
async fn ask(app: &AppHandle, title: &str, message: String, buttons: MessageDialogButtons, kind: MessageDialogKind) -> bool {
    let dialog = app.dialog().message(message).title(title).kind(kind).buttons(buttons);
    tauri::async_runtime::spawn_blocking(move || dialog.blocking_show())
        .await
        .unwrap_or(false)
}

/// Looks for a newer version on GitHub and, if the person agrees, downloads
/// it (signature-checked by the updater), installs it and reopens the app.
/// `manual` = they asked: then "you're up to date" and errors are shown too.
async fn check_for_update(app: &AppHandle, manual: bool) {
    let found = match app.updater() {
        Ok(updater) => updater.check().await,
        Err(err) => Err(err),
    };
    let update = match found {
        Ok(Some(update)) => update,
        Ok(None) => {
            if manual {
                let version = app.package_info().version.to_string();
                ask(
                    app,
                    "No update",
                    format!("You have the latest version of Proggaa ({version})."),
                    MessageDialogButtons::Ok,
                    MessageDialogKind::Info,
                )
                .await;
            }
            return;
        }
        Err(err) => {
            if manual {
                ask(
                    app,
                    "Couldn't check for updates",
                    format!("Check your internet connection and try again.\n\n({err})"),
                    MessageDialogButtons::Ok,
                    MessageDialogKind::Warning,
                )
                .await;
            }
            return;
        }
    };

    let notes = update.body.clone().unwrap_or_default();
    let message = format!(
        "Proggaa {} is ready (you have {}).\n\n{}{}The app will close, update and open again by itself.",
        update.version,
        update.current_version,
        notes.trim(),
        if notes.trim().is_empty() { "" } else { "\n\n" },
    );
    let yes = ask(
        app,
        "Update available",
        message,
        MessageDialogButtons::OkCancelCustom("Update now".into(), "Later".into()),
        MessageDialogKind::Info,
    )
    .await;
    if !yes {
        return;
    }

    let window = app.get_webview_window("main");
    let mut downloaded: u64 = 0;
    let result = update
        .download_and_install(
            |chunk, total| {
                downloaded += chunk as u64;
                if let (Some(window), Some(total)) = (&window, total) {
                    if total > 0 {
                        let _ = window.set_title(&format!("Proggaa — downloading update {}%", downloaded * 100 / total));
                    }
                }
            },
            || {},
        )
        .await;

    match result {
        // On Windows the installer takes over from here and reopens the app;
        // restarting covers the case where it hands back control instead.
        Ok(()) => app.restart(),
        Err(err) => {
            if let Some(window) = &window {
                let _ = window.set_title("Proggaa");
            }
            ask(
                app,
                "Update failed",
                format!("The update couldn't be installed. Proggaa will try again later.\n\n({err})"),
                MessageDialogButtons::Ok,
                MessageDialogKind::Error,
            )
            .await;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn opens(u: &str) -> bool {
        opens_in_app(&Url::parse(u).unwrap())
    }

    #[test]
    fn keeps_the_site_in_the_app() {
        assert!(opens("https://prooggaa.com/community"));
        assert!(opens("https://www.prooggaa.com/"));
        assert!(opens("http://tauri.localhost/index.html"));
        assert!(opens("tauri://localhost/index.html"));
        assert!(opens("https://accounts.google.com/o/oauth2/auth"));
    }

    #[test]
    fn sends_everything_else_to_the_browser() {
        assert!(!opens("https://www.youtube.com/watch?v=x"));
        assert!(!opens("https://prooggaa.com.evil.example/"));
        assert!(!opens("https://evilprooggaa.com/"));
        assert!(!opens("mailto:hi@prooggaa.com"));
        assert!(!opens("proggaa://update"));
    }

    #[test]
    fn tags_the_user_agent() {
        assert!(user_agent("1.2.3").ends_with("ProggaaDesktop/1.2.3"));
    }
}
