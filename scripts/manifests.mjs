// Builds the two update manifests a release publishes:
//   latest.json          read by the Windows app (Tauri updater format)
//   android-latest.json  read by the Android app (AppUpdater.kt)
// Pure functions, so they can be tested (manifests.test.mjs).

export const REPO = "rahmannaiku-ux/proggaa-apps";

/** 1.2.3 -> 10203 (the Android versionCode; the same rule as app/build.gradle.kts). */
export function versionCode(version) {
  const m = /^v?(\d+)\.(\d+)\.(\d+)$/.exec(String(version).trim());
  if (!m) throw new Error(`Not a MAJOR.MINOR.PATCH version: ${version}`);
  const [major, minor, patch] = m.slice(1).map(Number);
  if (minor > 99 || patch > 99) throw new Error(`Minor and patch must be 0-99 (got ${version})`);
  return major * 10000 + minor * 100 + patch;
}

export function downloadUrl(tag, asset) {
  return `https://github.com/${REPO}/releases/download/${tag}/${encodeURIComponent(asset)}`;
}

export function desktopManifest({ version, tag, notes, pubDate, installer, signature }) {
  if (!signature || !signature.trim()) throw new Error("The Windows installer has no updater signature (.sig)");
  return {
    version,
    notes,
    pub_date: pubDate,
    platforms: {
      "windows-x86_64": { signature: signature.trim(), url: downloadUrl(tag, installer) },
    },
  };
}

export function androidManifest({ version, tag, notes, pubDate, apk, sha256 }) {
  if (!/^[0-9a-f]{64}$/.test(sha256)) throw new Error("The APK checksum must be 64 lowercase hex characters");
  return {
    versionCode: versionCode(version),
    versionName: version,
    url: downloadUrl(tag, apk),
    sha256,
    notes,
    pub_date: pubDate,
  };
}
