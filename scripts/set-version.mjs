// Sets the version of both apps (the root package.json is the only place it lives).
//   node scripts/set-version.mjs 1.0.1
import { readFileSync, writeFileSync } from "node:fs";
import { versionCode } from "./manifests.mjs";

const next = (process.argv[2] ?? "").replace(/^v/, "");
versionCode(next); // throws on a malformed version
const file = new URL("../package.json", import.meta.url);
const pkg = JSON.parse(readFileSync(file, "utf8"));
if (versionCode(next) <= versionCode(pkg.version)) {
  throw new Error(`${next} must be higher than the current ${pkg.version}, or installed apps won't see it as an update`);
}
pkg.version = next;
writeFileSync(file, JSON.stringify(pkg, null, 2) + "\n");
console.log(`Version set to ${next}. Now commit, then: git tag v${next} && git push && git push origin v${next}`);
