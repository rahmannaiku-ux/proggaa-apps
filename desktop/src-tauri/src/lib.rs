//! Proggaa for Windows: one window showing prooggaa.com, plus the things a
//! browser tab can't do — its own icon and taskbar entry, one copy at a
//! time, remembering its size, and updating itself from GitHub Releases.
//!
//! The site gets no access to Tauri's API (there is no capability for remote
//! pages): everything the app does natively happens here in Rust.

use std::sync::Mutex;
use std::time::Duration;

use serde::Serialize;
use tauri::webview::NewWindowResponse;
use tauri::{AppHandle, Emitter, Manager, State, WebviewUrl, WebviewWindowBuilder};
use tauri_plugin_dialog::{DialogExt, MessageDialogButtons, MessageDialogKind};
use tauri_plugin_opener::OpenerExt;
use tauri_plugin_updater::{Update, UpdaterExt};
use url::Url;

/// The update found by the last check, waiting for the person to choose in the update window.
#[derive(Default)]
struct PendingUpdate(Mutex<Option<Update>>);

/// The label of the update window (`dist/update.html`); the only window allowed to call the update commands.
const UPDATE_WINDOW: &str = "updater";

/// The site the app shows.
pub const SITE: &str = "https://prooggaa.com";

/// A link the site uses (on /download, when it sees it is inside the app)
/// to ask the app to look for an update now.
const CHECK_UPDATE_URL: &str = "proggaa://update";

/// `proggaa://browser?url=<https address>`: the site asks for a page to open in
/// the person's normal browser (e.g. buying a mission, which is done on the website).
pub fn browser_target(url: &Url) -> Option<String> {
    if url.scheme() != "proggaa" || url.host_str() != Some("browser") {
        return None;
    }
    let target = url.query_pairs().find(|(k, _)| k == "url")?.1.into_owned();
    let parsed = Url::parse(&target).ok()?;
    (parsed.scheme() == "https").then_some(target)
}

/// Wait this long after launch before the first update check, so it never
/// slows the site down while it loads.
const FIRST_CHECK_AFTER: Duration = Duration::from_secs(10);
/// Look again this often while the app stays open.
const CHECK_EVERY: Duration = Duration::from_secs(6 * 60 * 60);

/// The title bar's colour: the site's sidebar purple (#271643, as 0x00BBGGRR),
/// so the top of the window reads as part of the app rather than a black strip.
#[cfg(windows)]
const TITLE_BAR_COLOR: u32 = 0x0043_1627;

/// Paints the Windows title bar in the sidebar's purple with white text
/// (Windows 11; older Windows keeps a dark title bar).
#[cfg(windows)]
fn paint_title_bar(window: &tauri::WebviewWindow) {
    use windows_sys::Win32::Graphics::Dwm::{
        DwmSetWindowAttribute, DWMWA_BORDER_COLOR, DWMWA_CAPTION_COLOR, DWMWA_TEXT_COLOR, DWMWA_USE_IMMERSIVE_DARK_MODE,
    };
    let Ok(hwnd) = window.hwnd() else { return };
    let set = |attribute: i32, value: u32| unsafe {
        DwmSetWindowAttribute(hwnd.0 as _, attribute as _, &value as *const u32 as _, 4);
    };
    // Light minimise/maximise/close buttons, whatever the Windows theme.
    set(DWMWA_USE_IMMERSIVE_DARK_MODE as i32, 1);
    set(DWMWA_CAPTION_COLOR as i32, TITLE_BAR_COLOR);
    set(DWMWA_BORDER_COLOR as i32, TITLE_BAR_COLOR);
    set(DWMWA_TEXT_COLOR as i32, 0x00FF_FFFF);
}

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
        .manage(PendingUpdate::default())
        .invoke_handler(tauri::generate_handler![update_info, start_update, dismiss_update])
        .setup(|app| {
            let handle = app.handle().clone();
            let version = app.package_info().version.to_string();
            let nav_handle = handle.clone();
            let popup_handle = handle.clone();

            let main = WebviewWindowBuilder::new(app, "main", WebviewUrl::App("index.html".into()))
                .title("Proggaa")
                // Opens maximized the first time; after that the window-state plugin
                // brings back the size and position it was closed at.
                .inner_size(1200.0, 760.0)
                .min_inner_size(420.0, 560.0)
                .center()
                .maximized(true)
                .user_agent(&user_agent(&version))
                .on_navigation(move |url| {
                    if let Some(target) = browser_target(url) {
                        let _ = nav_handle.opener().open_url(target, None::<&str>);
                        return false;
                    }
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
                // target="_blank" links and window.open: pages of the site (a full-size picture,
                // a file) open in a second app window that shares the sign-in; the rest in the browser.
                .on_new_window(move |url, _features| {
                    if opens_in_app(&url) {
                        return NewWindowResponse::Allow;
                    }
                    if matches!(url.scheme(), "http" | "https" | "mailto" | "tel") {
                        let _ = popup_handle.opener().open_url(url.as_str(), None::<&str>);
                    }
                    NewWindowResponse::Deny
                })
                .build()?;
            #[cfg(windows)]
            paint_title_bar(&main);
            #[cfg(not(windows))]
            let _ = main;

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

    if let Some(state) = app.try_state::<PendingUpdate>() {
        *state.0.lock().unwrap() = Some(update);
    }
    show_update_window(app);
}

/// Opens the update window (or brings it to the front if it is already open).
fn show_update_window(app: &AppHandle) {
    if let Some(window) = app.get_webview_window(UPDATE_WINDOW) {
        let _ = window.unminimize();
        let _ = window.set_focus();
        return;
    }
    let built = WebviewWindowBuilder::new(app, UPDATE_WINDOW, WebviewUrl::App("update.html".into()))
        .title("Update Proggaa")
        .inner_size(400.0, 500.0)
        .resizable(false)
        .maximizable(false)
        .minimizable(false)
        .decorations(false)
        .always_on_top(true)
        .center()
        .focused(true)
        .build();
    if let Err(err) = built {
        eprintln!("could not open the update window: {err}");
    }
}

#[derive(Serialize)]
struct UpdateInfo {
    current: String,
    version: String,
    notes: String,
}

/// What the update window shows: the installed version, the new one and what's new.
#[tauri::command]
fn update_info(state: State<'_, PendingUpdate>) -> Result<UpdateInfo, String> {
    let guard = state.0.lock().unwrap();
    let update = guard.as_ref().ok_or("No update is waiting.")?;
    Ok(UpdateInfo {
        current: update.current_version.clone(),
        version: update.version.clone(),
        notes: update.body.clone().unwrap_or_default().trim().to_string(),
    })
}

#[derive(Clone, Serialize)]
struct Progress {
    downloaded: u64,
    total: Option<u64>,
}

/// Downloads the waiting update (checked against the updater key) while the
/// window shows progress, then installs it; the installer reopens the app.
#[tauri::command]
fn start_update(app: AppHandle, state: State<'_, PendingUpdate>) -> Result<(), String> {
    let update = state.0.lock().unwrap().clone().ok_or("No update is waiting.")?;
    tauri::async_runtime::spawn(async move {
        let mut downloaded: u64 = 0;
        let mut last_percent: Option<u64> = None;
        let progress_app = app.clone();
        let done_app = app.clone();
        let result = update
            .download_and_install(
                move |chunk, total| {
                    downloaded += chunk as u64;
                    // At most one message per whole percent (or per ~256 KB when the size is unknown).
                    let step = match total {
                        Some(t) if t > 0 => downloaded * 100 / t,
                        _ => downloaded / (256 * 1024),
                    };
                    if last_percent != Some(step) {
                        last_percent = Some(step);
                        let _ = progress_app.emit_to(UPDATE_WINDOW, "update-progress", Progress { downloaded, total });
                    }
                },
                move || {
                    let _ = done_app.emit_to(UPDATE_WINDOW, "update-installing", ());
                },
            )
            .await;
        match result {
            // On Windows the installer takes over from here and reopens the app;
            // restarting covers the case where it hands back control instead.
            Ok(()) => app.restart(),
            Err(err) => {
                let _ = app.emit_to(UPDATE_WINDOW, "update-error", err.to_string());
            }
        }
    });
    Ok(())
}

/// "Later": closes the update window. The app asks again at the next check.
#[tauri::command]
fn dismiss_update(app: AppHandle) {
    if let Some(window) = app.get_webview_window(UPDATE_WINDOW) {
        let _ = window.close();
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
    fn opens_website_only_pages_in_the_browser() {
        let target = |u: &str| browser_target(&Url::parse(u).unwrap());
        assert_eq!(
            target("proggaa://browser?url=https%3A%2F%2Fprooggaa.com%2Fcourses%2Ffull-stack").as_deref(),
            Some("https://prooggaa.com/courses/full-stack")
        );
        assert_eq!(target("proggaa://browser?url=javascript%3Aalert(1)"), None);
        assert_eq!(target("proggaa://browser?url=file%3A%2F%2F%2FC%3A%2Fx"), None);
        assert_eq!(target("proggaa://update"), None);
        assert_eq!(target("https://prooggaa.com/"), None);
    }

    #[test]
    fn tags_the_user_agent() {
        assert!(user_agent("1.2.3").ends_with("ProggaaDesktop/1.2.3"));
    }
}
