# Releasing Lilyscan

How the published downloads are made, and the one-time setup only the repository owner can do.

| What | Where | Made by |
|---|---|---|
| Server image, newest | `ghcr.io/vilpter/lilyscan:latest` | `publish-image.yml`, on every push to `main` |
| Server image, a release | `ghcr.io/vilpter/lilyscan:0.2.0` (and `:0.2`) | `release.yml` |
| Desktop app `.deb` / `.rpm` | the GitHub Release `v0.2.0` | `release.yml` |
| apt repository | the GitHub Release named `apt` | `release.yml` |

The apt repository is a signed *flat* repository kept as the assets of one release named `apt`:
the newest `.deb`, its index (`Packages`, `Release`, `InRelease`, `Release.gpg`) and the public key.
It lives on a release rather than GitHub Pages because the `.deb` is over 200 MB, and a file in a
repository (which Pages serves) may be at most 100 MB. Users add it once and then get updates with
`sudo apt update && sudo apt upgrade` ([INSTALL.md](INSTALL.md)).

## One-time setup

### 1. The apt signing key

apt only installs from a repository whose index is signed by a key the user trusts. Make a key
for Lilyscan's repository on your own computer (any Linux or macOS machine with GnuPG; on Windows,
WSL). Nobody else ever needs the private part.

```bash
# A key used only to sign the Lilyscan repository, valid for 5 years.
gpg --quick-gen-key "Lilyscan apt repository <you@example.com>" rsa4096 sign 5y
```

GnuPG asks for a passphrase. Either set one (then also store it as a secret, below) or leave it
empty and confirm. Then find the key's fingerprint and export the private key:

```bash
gpg --list-secret-keys --keyid-format long "Lilyscan apt repository"
# the long hexadecimal line under "sec" is the fingerprint
gpg --armor --export-secret-keys FINGERPRINT > lilyscan-apt-private.asc
```

Store it as repository secrets: **github.com/vilpter/lilyscan → Settings → Secrets and variables →
Actions → New repository secret**:

- Name `APT_SIGNING_KEY`, value: the whole content of `lilyscan-apt-private.asc`, including the
  `-----BEGIN PGP PRIVATE KEY BLOCK-----` and `-----END ...-----` lines.
- Only if you set a passphrase: name `APT_SIGNING_PASSPHRASE`, value: the passphrase.

Then **keep a copy of `lilyscan-apt-private.asc` somewhere safe** (a password manager, an encrypted
USB stick) and delete it from the computer. If the key is lost, users would have to fetch a new
public key before updates work again (INSTALL.md, "If apt says the signature is invalid").

The key expires in 5 years. Before then, extend it (`gpg --quick-set-expire FINGERPRINT 5y`),
export it again into the secret, and make a release: the new public key is published with it.

### 2. Make the image public (after the first image is published)

GitHub creates the image private, so `docker compose pull` would ask for a login. Once
`publish-image` has run on `main` for the first time (check the Actions tab):

1. Open **github.com/vilpter → Packages → lilyscan → Package settings** (right-hand side).
2. Under **Danger Zone → Change package visibility**, choose **Public**, type `lilyscan`, confirm.

This is permanent for the package: a public package cannot be made private again.

## Making a release

1. Make sure `main` is green on the Actions tab.
2. Choose the version, e.g. `0.2.0` (numbers and dots; each release must be higher than the last,
   or apt does not offer it as an update).
3. Start it, either way:
   - **Actions tab → release → Run workflow**, enter `0.2.0`, **Run workflow**; or
   - from a clone: `git tag v0.2.0 && git push origin v0.2.0`.
4. Wait for the three jobs (`version`, `packages`, `image`), about 20 minutes. Then check:
   - **Releases** shows `Lilyscan 0.2.0` with the `.deb`, `.rpm` and `docker-compose.yml`;
   - the `apt` release holds `lilyscan_0.2.0_amd64.deb` and freshly dated index files;
   - **Packages → lilyscan** lists the tag `0.2.0`.

The `apt` release is marked "not latest", so the numbered release stays the one GitHub shows as
latest. Do not delete the `apt` release or its `lilyscan-archive-keyring.gpg`: every user's apt
points at them.

## If a release fails

- **"The repository secret APT_SIGNING_KEY is missing"**: do step 1 of the setup, then run the
  workflow again with the same version.
- **Anything else**: fix it on `main`, then run the workflow again with the same version. It reuses
  the release if it already exists and replaces its files. (If the tag already points at the wrong
  commit, delete the release and the tag on GitHub first.)
