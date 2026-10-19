// Used by the release workflow after both apps are built. Reads the build
// output in --in, and writes everything a GitHub Release needs into --out:
//   Proggaa_<v>_x64-setup.exe (+ .sig)  Windows installer the updater downloads
//   Proggaa-Setup.exe                   same installer, stable name for the website
//   Proggaa-<v>.apk                     Android app the updater downloads
//   Proggaa.apk                         same APK, stable name for the website
//   latest.json / android-latest.json   the update manifests
//
// node scripts/make-manifests.mjs --version 1.0.0 --tag v1.0.0 --in build --out release-assets --notes-file notes.md

import { createHash } from "node:crypto";
import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { androidManifest, desktopManifest } from "./manifests.mjs";

const args = Object.fromEntries(
  process.argv.slice(2).reduce((pairs, arg, i, all) => (arg.startsWith("--") ? [...pairs, [arg.slice(2), all[i + 1]]] : pairs), []),
);
for (const key of ["version", "tag", "in", "out"]) {
  if (!args[key]) throw new Error(`Missing --${key}`);
}
const { version, tag } = args;
const notes = args["notes-file"] && existsSync(args["notes-file"]) ? readFileSync(args["notes-file"], "utf8").trim() : "";
const pubDate = new Date().toISOString().replace(/\.\d+Z$/, "Z");

const files = readdirSync(args.in, { recursive: true }).map(String);
const find = (test, what) => {
  const hits = files.filter(test);
  if (hits.length !== 1) throw new Error(`Expected exactly one ${what} in ${args.in}, found: ${hits.join(", ") || "none"}`);
  return join(args.in, hits[0]);
};

const installerPath = find((f) => f.endsWith(`_${version}_x64-setup.exe`), "Windows installer");
const signaturePath = find((f) => f.endsWith(`_${version}_x64-setup.exe.sig`), "installer signature");
const apkPath = find((f) => f.endsWith(".apk"), "APK");

mkdirSync(args.out, { recursive: true });
const installer = `Proggaa_${version}_x64-setup.exe`;
const apk = `Proggaa-${version}.apk`;
copyFileSync(installerPath, join(args.out, installer));
copyFileSync(signaturePath, join(args.out, `${installer}.sig`));
copyFileSync(installerPath, join(args.out, "Proggaa-Setup.exe"));
copyFileSync(apkPath, join(args.out, apk));
copyFileSync(apkPath, join(args.out, "Proggaa.apk"));

const sha256 = createHash("sha256").update(readFileSync(apkPath)).digest("hex");
const desktop = desktopManifest({ version, tag, notes, pubDate, installer, signature: readFileSync(signaturePath, "utf8") });
const android = androidManifest({ version, tag, notes, pubDate, apk, sha256 });
writeFileSync(join(args.out, "latest.json"), JSON.stringify(desktop, null, 2) + "\n");
writeFileSync(join(args.out, "android-latest.json"), JSON.stringify(android, null, 2) + "\n");

console.log(readdirSync(args.out).join("\n"));
