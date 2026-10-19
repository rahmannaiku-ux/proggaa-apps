import { test } from "node:test";
import assert from "node:assert/strict";
import { androidManifest, desktopManifest, downloadUrl, versionCode } from "./manifests.mjs";

test("version codes grow with the version", () => {
  assert.equal(versionCode("1.0.0"), 10000);
  assert.equal(versionCode("v1.2.3"), 10203);
  assert.ok(versionCode("1.10.0") > versionCode("1.9.99"));
  assert.throws(() => versionCode("1.2"));
  assert.throws(() => versionCode("1.100.0"));
});

test("download links point at the tag's release", () => {
  assert.equal(
    downloadUrl("v1.0.0", "Proggaa_1.0.0_x64-setup.exe"),
    "https://github.com/rahmannaiku-ux/proggaa-apps/releases/download/v1.0.0/Proggaa_1.0.0_x64-setup.exe",
  );
});

test("the Windows manifest is in the Tauri updater format", () => {
  const m = desktopManifest({
    version: "1.0.0",
    tag: "v1.0.0",
    notes: "First release",
    pubDate: "2026-10-19T00:00:00Z",
    installer: "Proggaa_1.0.0_x64-setup.exe",
    signature: "c2lnbmF0dXJl\n",
  });
  assert.equal(m.platforms["windows-x86_64"].signature, "c2lnbmF0dXJl");
  assert.match(m.platforms["windows-x86_64"].url, /\/v1\.0\.0\/Proggaa_1\.0\.0_x64-setup\.exe$/);
  assert.throws(() => desktopManifest({ version: "1.0.0", tag: "v1.0.0", installer: "x.exe", signature: "" }));
});

test("the Android manifest carries a version code and checksum", () => {
  const m = androidManifest({
    version: "1.2.3",
    tag: "v1.2.3",
    notes: "",
    pubDate: "2026-10-19T00:00:00Z",
    apk: "Proggaa-1.2.3.apk",
    sha256: "a".repeat(64),
  });
  assert.equal(m.versionCode, 10203);
  assert.equal(m.versionName, "1.2.3");
  assert.throws(() => androidManifest({ version: "1.2.3", tag: "v1.2.3", apk: "x.apk", sha256: "nope" }));
});
